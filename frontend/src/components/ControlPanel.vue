<template>
  <div class="control-panel">
    <h3 class="panel-title">治疗控制</h3>
    <div class="btn-grid">
      <button class="btn btn-start" @click="startDialysis" :disabled="loading">
        ▶ 开始透析
      </button>
      <button class="btn btn-stop" @click="stopDialysis" :disabled="loading">
        ■ 停止治疗
      </button>
      <button class="btn btn-tare" @click="tareSensors" :disabled="loading">
        ⚖ 传感器去皮
      </button>
      <button class="btn btn-danger" @click="emergencyStop" :disabled="loading">
        ⚠ 紧急停止
      </button>
    </div>
    <div class="divider"></div>
    <div class="device-list">
      <h4 class="subtitle">硬件状态</h4>
      <div v-for="device in devices" :key="device.nodeId" class="device-item">
        <div class="device-info">
          <span class="device-icon">{{ deviceIcon(device.deviceType) }}</span>
          <span class="device-name">{{ deviceName(device.deviceType) }} #{{ device.nodeId }}</span>
        </div>
        <div class="device-state" :class="deviceStateClass(device)">
          {{ device.online ? (device.state === 'RUNNING' ? '运行中' : device.state === 'STANDBY' ? '待机' : device.state) : '离线' }}
        </div>
        <div class="device-values" v-if="device.deviceType !== 'WEIGHT_SENSOR'">
          {{ device.currentValue.toFixed(1) }} / {{ device.targetValue.toFixed(1) }}
          <span class="unit">{{ deviceUnit(device.deviceType) }}</span>
        </div>
      </div>
    </div>
    <div class="divider"></div>
    <div class="stats">
      <div class="stat">
        <span class="stat-label">缓冲队列</span>
        <span class="stat-value">{{ status.bufferSize }}</span>
      </div>
      <div class="stat">
        <span class="stat-label">毛刺抑制率</span>
        <span class="stat-value green">{{ (status.spikeSuppressionRate * 100).toFixed(2) }}%</span>
      </div>
      <div class="stat">
        <span class="stat-label">已抑制毛刺</span>
        <span class="stat-value">{{ status.spikesSuppressed }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { dialysisStore } from '../store.js'
import { dialysisApi } from '../api.js'

const loading = ref(false)
const devices = computed(() => dialysisStore.devices)
const status = computed(() => dialysisStore.status)

async function startDialysis() {
  loading.value = true
  try {
    await dialysisApi.tareSensors()
    await dialysisApi.sendCommand('START_DIALYSIS', 1, 0)
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

async function stopDialysis() {
  loading.value = true
  try {
    await dialysisApi.sendCommand('STOP_DIALYSIS', 1, 0)
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

async function emergencyStop() {
  loading.value = true
  try {
    await dialysisApi.sendCommand('EMERGENCY_STOP', 1, 0)
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

async function tareSensors() {
  loading.value = true
  try {
    await dialysisApi.tareSensors()
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

function deviceIcon(type) {
  return { PERISTALTIC_PUMP: '⚙', HEATING_BAG: '🌡', WEIGHT_SENSOR: '⚖' }[type] || '●'
}

function deviceName(type) {
  return { PERISTALTIC_PUMP: '蠕动泵', HEATING_BAG: '加热袋', WEIGHT_SENSOR: '称重传感器' }[type] || type
}

function deviceUnit(type) {
  return { PERISTALTIC_PUMP: 'ml/min', HEATING_BAG: '°C', WEIGHT_SENSOR: 'g' }[type] || ''
}

function deviceStateClass(d) {
  if (!d.online) return 'offline'
  if (d.state === 'RUNNING') return 'running'
  if (d.state === 'ERROR') return 'error'
  return 'standby'
}
</script>

<style scoped>
.control-panel {
  padding: 16px 20px;
  background: linear-gradient(135deg, rgba(30, 41, 59, 0.6), rgba(15, 23, 42, 0.8));
  border: 1px solid rgba(186, 104, 200, 0.2);
  border-radius: 12px;
  backdrop-filter: blur(8px);
  height: 100%;
  overflow-y: auto;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #e0e6ed;
  margin-bottom: 12px;
  letter-spacing: 0.5px;
}

.subtitle {
  font-size: 12px;
  color: #78909c;
  margin-bottom: 10px;
  font-weight: 500;
  letter-spacing: 0.3px;
}

.btn-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.btn {
  padding: 10px 8px;
  border: none;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  color: #fff;
  letter-spacing: 0.3px;
}

.btn:disabled { opacity: 0.5; cursor: not-allowed; }

.btn-start { background: linear-gradient(135deg, #00c853, #00a844); }
.btn-start:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 4px 14px rgba(0, 200, 83, 0.4); }

.btn-stop { background: linear-gradient(135deg, #455a64, #37474f); }
.btn-stop:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 4px 14px rgba(69, 90, 100, 0.4); }

.btn-tare { background: linear-gradient(135deg, #5c6bc0, #3949ab); }
.btn-tare:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 4px 14px rgba(92, 107, 192, 0.4); }

.btn-danger { background: linear-gradient(135deg, #ff5252, #d32f2f); }
.btn-danger:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 4px 14px rgba(255, 82, 82, 0.5); }

.divider {
  height: 1px;
  background: rgba(255, 255, 255, 0.06);
  margin: 14px 0;
}

.device-list { margin-top: 4px; }

.device-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  background: rgba(10, 14, 26, 0.4);
  border-radius: 8px;
  margin-bottom: 6px;
}

.device-info {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 110px;
}

.device-icon { font-size: 14px; }
.device-name { font-size: 12px; color: #b0bec5; font-weight: 500; }

.device-state {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 600;
  min-width: 48px;
  text-align: center;
}

.device-state.running { background: rgba(0, 230, 118, 0.15); color: #00e676; }
.device-state.standby { background: rgba(120, 144, 156, 0.2); color: #90a4ae; }
.device-state.offline { background: rgba(255, 82, 82, 0.15); color: #ff5252; }
.device-state.error { background: rgba(255, 152, 0, 0.2); color: #ff9800; }

.device-values {
  margin-left: auto;
  font-size: 12px;
  color: #e0e6ed;
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.unit {
  font-size: 10px;
  color: #78909c;
  font-weight: 400;
  margin-left: 3px;
}

.stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  text-align: center;
}

.stat-label { font-size: 10px; color: #78909c; letter-spacing: 0.3px; }
.stat-value { font-size: 15px; font-weight: 700; color: #e0e6ed; font-variant-numeric: tabular-nums; }
.stat-value.green { color: #00e676; }
</style>
