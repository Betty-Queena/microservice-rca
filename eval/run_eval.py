#!/usr/bin/env python3
"""Day 7 评测 CLI（P0 第二阶段）。

支持：
- validate: 校验 case 文件；
- plan: 打印运行计划与调用量估算，不执行实验；
- run: 通过真实 HTTP API 执行黑盒评测，必须显式传 --yes；
- rescore: 对已有本地原始结果做离线重判。

run 会连接本地 backend/order-service/inventory-service，并通过后端 Agent
诊断接口间接触发模型调用；脚本本身不读取或打印任何 API Key。
"""

from __future__ import annotations

import argparse
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, List, Optional

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from rca_eval.cases import load_raw_samples, load_suite, suite_warnings
from rca_eval.models import CaseValidationError
from rca_eval.report import write_reports
from rca_eval.runner import (
    DEFAULT_HTTP_TIMEOUT_SECONDS,
    DEFAULT_MAX_DIAGNOSIS_RUNS,
    DEFAULT_POLL_INTERVAL_SECONDS,
    BlackBoxEvaluationRunner,
    BudgetExceededError,
    RunConfig,
    make_run_id,
    run_suite,
    validate_run_budget,
)
from rca_eval.scoring import score_all

DEFAULT_SUITE = HERE / "cases" / "day7-baseline.json"
DEFAULT_RESULTS_DIR = HERE / "results"

# 仅用于 plan 的粗略估算，来源为 Day 6 一次真实 HTTP 500 诊断的观测值。
# 这不是本轮实验数据，也不能作为论文结果。
EST_MODEL_CALLS_PER_DIAGNOSIS = 4
EST_TOKENS_PER_DIAGNOSIS = 10824


def _print_suite(suite) -> None:
    print("suite: %s" % suite.suite_id)
    print("labelVersion: %s" % suite.label_version)
    print("labelSource: %s" % suite.label_source)
    print("cases: %d" % len(suite.cases))
    for case in suite.cases:
        injection = "none"
        if case.injection is not None:
            injection = case.injection.fault_type
            if case.injection.delay_ms is not None:
                injection += "(%dms)" % case.injection.delay_ms
        print("  - %-22s scenario=%-8s diagnosis=%-5s injection=%s expected=%s" % (
            case.case_id,
            case.scenario,
            str(case.diagnosis).lower(),
            injection,
            case.expected.outcome,
        ))


def cmd_validate(args: argparse.Namespace) -> int:
    suite = load_suite(args.suite)
    _print_suite(suite)
    warnings = suite_warnings(suite)
    if warnings:
        print("warnings:")
        for warning in warnings:
            print("  - %s" % warning)
    else:
        print("warnings: none")
    return 0


def cmd_plan(args: argparse.Namespace) -> int:
    suite = load_suite(args.suite)
    repeats = args.repeats if args.repeats is not None else suite.defaults.repeat
    if repeats < 1:
        print("repeats 必须大于等于 1", file=sys.stderr)
        return 2

    diagnosis_cases = [case for case in suite.cases if case.diagnosis]
    normal_cases = [case for case in suite.cases if not case.diagnosis]
    diagnosis_runs = len(diagnosis_cases) * repeats
    normal_runs = len(normal_cases) * repeats

    _print_suite(suite)
    print("repeats: %d" % repeats)
    print("planned diagnosis runs: %d" % diagnosis_runs)
    print("planned normal runs: %d (no diagnosis call)" % normal_runs)
    print("estimated model calls: %d (based on Day 6, not this run's data)" % (
        diagnosis_runs * EST_MODEL_CALLS_PER_DIAGNOSIS))
    print("estimated tokens: %d (based on Day 6, not this run's data)" % (
        diagnosis_runs * EST_TOKENS_PER_DIAGNOSIS))
    print("note: plan 模式不执行故障注入、不调用后端、不调用 DeepSeek。")
    return 0


def cmd_run(args: argparse.Namespace) -> int:
    # 先校验标签，避免用坏 case 发起真实故障注入。
    suite = load_suite(args.suite)
    _print_suite(suite)

    if not args.yes:
        print("")
        print("拒绝执行真实实验：run 必须显式传入 --yes。", file=sys.stderr)
        print("如需只查看计划，请执行: python eval/run_eval.py plan --repeats 1",
              file=sys.stderr)
        return 2

    # 真实运行默认只做一轮低成本冒烟；不跟随 suite 中用于统计稳定性的 repeat=3。
    repeats = args.repeats if args.repeats is not None else 1
    try:
        planned = validate_run_budget(suite, repeats, args.max_diagnosis_runs)
    except BudgetExceededError as exc:
        print("预算拒绝执行: %s" % exc, file=sys.stderr)
        return 2
    except ValueError as exc:
        print("参数错误: %s" % exc, file=sys.stderr)
        return 2

    run_id = args.run_id or make_run_id()
    results_dir = Path(args.results_dir)
    run_dir = results_dir / run_id
    if run_dir.exists():
        print("运行目录已存在，拒绝覆盖: %s" % run_dir, file=sys.stderr)
        return 2

    print("")
    print("runId: %s" % run_id)
    print("repeats: %d" % repeats)
    print("planned diagnosis runs: %d" % planned)
    print("max diagnosis runs: %d" % args.max_diagnosis_runs)
    print("backend: %s" % args.backend_url)
    print("order: %s" % args.order_url)
    print("inventory: %s" % args.inventory_url)
    print("results: %s" % run_dir)
    print("note: 执行会真实注入故障并可能产生 DeepSeek 费用；故障按串行清理。")

    config = RunConfig(
        backend_url=args.backend_url,
        order_url=args.order_url,
        inventory_url=args.inventory_url,
        results_dir=results_dir,
        run_id=run_id,
        repeats=repeats,
        max_diagnosis_runs=args.max_diagnosis_runs,
        poll_interval_seconds=args.poll_interval_seconds,
        diagnosis_timeout_seconds=args.diagnosis_timeout_seconds,
        http_timeout_seconds=args.http_timeout_seconds,
    )
    runner = BlackBoxEvaluationRunner(
        config=config,
        raw_dir=run_dir / "raw",
        defaults=suite.defaults,
    )

    print("")
    print("preflight: backend / order-service / inventory-service / fault injection ...")
    preflight = runner.preflight()
    print("preflight: OK")
    for name in ("backend", "order", "inventory"):
        details = preflight.get(name) or {}
        print("  - %-9s %s (%s)" % (
            name, details.get("status"), details.get("version") or "unknown"))

    result = run_suite(suite, runner, config, preflight=preflight)
    eligible = sum(1 for item in result.samples if item.harness_error is None)
    print("")
    print("samples written: %d (without harness error: %d)" % (
        len(result.samples), eligible))
    print("diagnosis runs started: %d" % result.diagnosis_runs_started)
    print("raw: %s" % (run_dir / "raw"))
    print("summary: %s" % result.report_paths["summaryJson"])
    print("csv: %s" % result.report_paths["summaryCsv"])
    print("samples: %s" % result.report_paths["samplesJsonl"])
    print("failures: %s" % result.report_paths["failuresMarkdown"])
    print("manifest: %s" % (run_dir / "run-manifest.json"))
    if result.aborted:
        print("")
        print("运行已中止: %s" % result.abort_reason, file=sys.stderr)
        print("已完成的原始样本和报告已保留；请检查环境后重新运行。",
              file=sys.stderr)
        return 3
    return 0


def _default_output(input_path: Path) -> Path:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    base = input_path if input_path.is_dir() else input_path.parent
    return base / ("rescore-" + timestamp)


def cmd_rescore(args: argparse.Namespace) -> int:
    suite = load_suite(args.suite)
    samples = load_raw_samples(args.input)
    results = score_all(suite, samples)

    input_path = Path(args.input)
    output_path = Path(args.output) if args.output else _default_output(input_path)
    paths = write_reports(
        output_path,
        suite,
        results,
        manifest_extra={
            "mode": "offline-rescore",
            "inputPath": str(input_path),
        },
    )

    eligible = sum(1 for result in results if result.eligible)
    diagnosis = sum(1 for result in results if result.is_diagnosis)
    normal = sum(1 for result in results if result.scenario == "NORMAL")
    normal_pass = sum(1 for result in results if result.sample_status == "NORMAL_PASS")
    false_alarm = sum(1 for result in results if result.sample_status == "FALSE_ALARM")
    strict_correct = sum(1 for result in results if result.strict_correct)

    print("rescore completed: %d samples (%d diagnosis, %d normal, %d unknown)" % (
        len(results), diagnosis, normal, len(results) - diagnosis - normal))
    print("eligible diagnosis samples: %d" % eligible)
    print("normal pass: %d, false alarms: %d" % (normal_pass, false_alarm))
    print("strict correct: %d" % strict_correct)
    print("summary: %s" % paths["summaryJson"])
    print("csv: %s" % paths["summaryCsv"])
    print("samples: %s" % paths["samplesJsonl"])
    print("failures: %s" % paths["failuresMarkdown"])
    return 0


def _add_suite_argument(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--suite", default=str(DEFAULT_SUITE),
        help="case 文件路径，默认 eval/cases/day7-baseline.json")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Day 7 评测工具（P0 第二阶段：真实黑盒运行需显式 --yes）")
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate", help="校验 case 文件")
    _add_suite_argument(validate_parser)
    validate_parser.set_defaults(func=cmd_validate)

    plan_parser = subparsers.add_parser("plan", help="打印运行计划，不执行实验")
    _add_suite_argument(plan_parser)
    plan_parser.add_argument(
        "--repeats", type=int, default=None, help="覆盖 defaults.repeat")
    plan_parser.set_defaults(func=cmd_plan)

    run_parser = subparsers.add_parser(
        "run", help="执行真实黑盒评测；必须显式传 --yes")
    _add_suite_argument(run_parser)
    run_parser.add_argument(
        "--yes", action="store_true",
        help="确认执行真实故障注入和 Agent 诊断；缺少该参数时拒绝运行")
    run_parser.add_argument(
        "--repeats", type=int, default=1,
        help="每个 case 的重复次数；默认 1，低成本冒烟（suite defaults.repeat=3 仅用于统计运行）")
    run_parser.add_argument(
        "--max-diagnosis-runs", type=int, default=DEFAULT_MAX_DIAGNOSIS_RUNS,
        help="本次运行允许启动的诊断次数上限（默认 %d）"
             % DEFAULT_MAX_DIAGNOSIS_RUNS)
    run_parser.add_argument(
        "--backend-url", default="http://localhost:8080",
        help="backend 根地址")
    run_parser.add_argument(
        "--order-url", default="http://localhost:8081",
        help="order-service 根地址，仅用于健康检查")
    run_parser.add_argument(
        "--inventory-url", default="http://localhost:8082",
        help="inventory-service 根地址，仅用于健康检查")
    run_parser.add_argument(
        "--results-dir", default=str(DEFAULT_RESULTS_DIR),
        help="结果根目录，默认 eval/results")
    run_parser.add_argument(
        "--run-id", default=None,
        help="运行 ID；默认自动生成，已存在的目录不会被覆盖")
    run_parser.add_argument(
        "--poll-interval-seconds", type=float,
        default=DEFAULT_POLL_INTERVAL_SECONDS,
        help="诊断任务轮询间隔秒数（默认 %.1f）" % DEFAULT_POLL_INTERVAL_SECONDS)
    run_parser.add_argument(
        "--diagnosis-timeout-seconds", type=int, default=None,
        help="单次诊断超时秒数；默认使用 suite defaults.diagnosis_timeout_seconds")
    run_parser.add_argument(
        "--http-timeout-seconds", type=float,
        default=DEFAULT_HTTP_TIMEOUT_SECONDS,
        help="单次 HTTP 请求超时秒数（默认 %.1f）" % DEFAULT_HTTP_TIMEOUT_SECONDS)
    run_parser.set_defaults(func=cmd_run)

    rescore_parser = subparsers.add_parser(
        "rescore", help="对已有本地原始结果做离线重判，不调用模型")
    _add_suite_argument(rescore_parser)
    rescore_parser.add_argument(
        "input", help="原始结果目录、.json 或 .jsonl 文件")
    rescore_parser.add_argument(
        "--output", default=None, help="报告输出目录；默认在输入路径旁生成 rescore-<时间>")
    rescore_parser.set_defaults(func=cmd_rescore)

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except CaseValidationError as exc:
        print("输入校验失败:", file=sys.stderr)
        for error in exc.errors:
            print("  - %s" % error, file=sys.stderr)
        return 1
    except FileNotFoundError as exc:
        print("文件不存在: %s" % exc, file=sys.stderr)
        return 1
    except Exception as exc:  # CLI 边界，避免把 traceback 直接暴露给使用者
        print("执行失败: %s" % exc, file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
