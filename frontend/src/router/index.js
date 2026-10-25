import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layouts/MainLayout.vue'
import OverviewView from '../views/OverviewView.vue'
import FaultInjectionView from '../views/FaultInjectionView.vue'
import FaultEventsView from '../views/FaultEventsView.vue'
import FaultEventDetailView from '../views/FaultEventDetailView.vue'
import ExternalServicesView from '../views/ExternalServicesView.vue'
import PlaceholderView from '../views/PlaceholderView.vue'

const routes = [
  {
    path: '/',
    component: MainLayout,
    redirect: '/overview',
    children: [
      {
        path: 'overview',
        name: 'overview',
        component: OverviewView,
        meta: { title: '系统概览' }
      },
      {
        path: 'fault-injection',
        name: 'fault-injection',
        component: FaultInjectionView,
        meta: { title: '故障注入实验' }
      },
      {
        path: 'fault-events',
        name: 'fault-events',
        component: FaultEventsView,
        meta: { title: '故障事件' }
      },
      {
        path: 'fault-events/:eventId',
        name: 'fault-event-detail',
        component: FaultEventDetailView,
        meta: { title: '故障事件详情' }
      },
      {
        path: 'external-services',
        name: 'external-services',
        component: ExternalServicesView,
        meta: { title: '外部服务' }
      },
      {
        path: 'diagnosis-tasks',
        name: 'diagnosis-tasks',
        component: PlaceholderView,
        meta: {
          title: '诊断任务',
          description: '展示诊断任务状态、Agent 推理过程、证据链与根因结论。',
          emptyText: '请从故障事件详情页的“Agent 诊断”标签启动并查看具体任务',
          planned: ['DeepSeek Agent 多轮推理', '证据链与根因结论', '人工状态确认与处置建议']
        }
      },
      {
        path: 'tool-invocations',
        name: 'tool-invocations',
        component: PlaceholderView,
        meta: {
          title: '工具调用',
          description: '审计 Agent 对日志、指标等诊断工具的真实调用记录。',
          emptyText: '证据采集与 Agent 诊断产生的工具调用已在故障事件详情中展示',
          planned: ['按诊断任务查看工具调用记录', '调用耗时与失败原因', '输入输出审计']
        }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/overview'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.afterEach((to) => {
  document.title = to.meta?.title
    ? `${to.meta.title} · 微服务故障分析与处置系统`
    : '微服务故障分析与处置系统'
})

export default router
