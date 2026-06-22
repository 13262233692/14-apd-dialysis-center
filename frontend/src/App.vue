<template>
  <div class="dashboard">
    <header class="dashboard-header">
      <div class="brand">
        <div class="logo">◉</div>
        <div class="brand-text">
          <h1>APD 智能腹膜透析</h1>
          <p>云端流控中心 · 居家肾脏替代治疗</p>
        </div>
      </div>
      <div class="header-time">{{ currentTime }}</div>
    </header>

    <main class="dashboard-main">
      <aside class="left-col">
        <StatusPanel />
        <div class="chart-wrapper">
          <NetUFChart />
        </div>
      </aside>
      <aside class="right-col">
        <ControlPanel />
      </aside>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import NetUFChart from './components/NetUFChart.vue'
import StatusPanel from './components/StatusPanel.vue'
import ControlPanel from './components/ControlPanel.vue'
import { connectWebSocket, disconnectWebSocket, dialysisStore } from './store.js'
import { dialysisApi } from './api.js'

const currentTime = ref('')
let timer = null

function updateTime() {
  currentTime.value = new Date().toLocaleString('zh-CN', { hour12: false })
}

onMounted(async () => {
  updateTime()
  timer = setInterval(updateTime, 1000)

  try {
    const [latest, history, devices] = await Promise.all([
      dialysisApi.getLatest(),
      dialysisApi.getHistory(120),
      dialysisApi.getDevices()
    ])
    dialysisStore.latest = latest
    dialysisStore.history = history
    dialysisStore.devices = devices
  } catch (e) {
    console.warn('Initial data load failed:', e)
  }

  connectWebSocket()
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  disconnectWebSocket()
})
</script>

<style scoped>
.dashboard {
  height: 100vh;
  width: 100vw;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(ellipse at top left, rgba(63, 81, 181, 0.15), transparent 50%),
    radial-gradient(ellipse at bottom right, rgba(156, 39, 176, 0.1), transparent 50%),
    #0a0e1a;
}

.dashboard-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 28px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
  background: rgba(10, 14, 26, 0.7);
  backdrop-filter: blur(12px);
}

.brand {
  display: flex;
  align-items: center;
  gap: 14px;
}

.logo {
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  color: #4fc3f7;
  background: linear-gradient(135deg, rgba(79, 195, 247, 0.2), rgba(63, 81, 181, 0.3));
  border-radius: 12px;
  box-shadow: 0 0 20px rgba(79, 195, 247, 0.2);
}

.brand-text h1 {
  font-size: 18px;
  font-weight: 700;
  color: #e0e6ed;
  letter-spacing: 0.5px;
}

.brand-text p {
  font-size: 12px;
  color: #78909c;
  margin-top: 2px;
}

.header-time {
  font-size: 14px;
  color: #b0bec5;
  font-variant-numeric: tabular-nums;
  font-weight: 500;
  letter-spacing: 0.5px;
}

.dashboard-main {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 340px;
  gap: 16px;
  padding: 16px 20px;
  overflow: hidden;
}

.left-col {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.right-col {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.chart-wrapper {
  flex: 1;
  min-height: 0;
  background: linear-gradient(135deg, rgba(30, 41, 59, 0.4), rgba(15, 23, 42, 0.6));
  border: 1px solid rgba(79, 195, 247, 0.15);
  border-radius: 12px;
  backdrop-filter: blur(8px);
  padding: 10px;
  overflow: hidden;
}
</style>
