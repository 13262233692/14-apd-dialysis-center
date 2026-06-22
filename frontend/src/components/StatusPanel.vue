<template>
  <div class="status-panel">
    <div class="status-row">
      <div class="status-indicator" :class="{ connected: connected }"></div>
      <span class="status-label">实时流</span>
      <span class="status-value">{{ connected ? '已连接' : '连接中...' }}</span>
    </div>
    <div v-if="latest" class="phase-row">
      <span class="phase-badge" :style="{ background: phaseColor, boxShadow: `0 0 12px ${phaseColor}66` }">
        {{ phaseLabel }}
      </span>
    </div>
    <div v-if="latest" class="metrics">
      <div class="metric">
        <span class="metric-label">净超滤</span>
        <span class="metric-value" :class="{ positive: latest.netUltrafiltrationMl >= 0, negative: latest.netUltrafiltrationMl < 0 }">
          {{ signed(latest.netUltrafiltrationMl) }} ml
        </span>
      </div>
      <div class="metric">
        <span class="metric-label">腹腔留置</span>
        <span class="metric-value purple">{{ latest.abdominalVolumeMl.toFixed(1) }} ml</span>
      </div>
      <div class="metric">
        <span class="metric-label">灌入总量</span>
        <span class="metric-value blue">{{ latest.inflowVolumeMl.toFixed(1) }} ml</span>
      </div>
      <div class="metric">
        <span class="metric-label">引流总量</span>
        <span class="metric-value orange">{{ latest.outflowVolumeMl.toFixed(1) }} ml</span>
      </div>
      <div class="metric">
        <span class="metric-label">透析液温度</span>
        <span class="metric-value">{{ latest.dialysateTemperatureC.toFixed(1) }} °C</span>
      </div>
      <div class="metric">
        <span class="metric-label">传感器质量</span>
        <span class="metric-value" :style="{ color: qualityColor }">{{ qualityLabel }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { dialysisStore } from '../store.js'
import { PHASE_LABELS, PHASE_COLORS, QUALITY_LABELS, QUALITY_COLORS } from '../config.js'

const connected = computed(() => dialysisStore.connected)
const latest = computed(() => dialysisStore.latest)

const phaseLabel = computed(() => latest.value ? (PHASE_LABELS[latest.value.phase] || latest.value.phase) : '--')
const phaseColor = computed(() => latest.value ? (PHASE_COLORS[latest.value.phase] || '#90a4ae') : '#90a4ae')
const qualityLabel = computed(() => latest.value ? (QUALITY_LABELS[latest.value.sensorQuality] || '--') : '--')
const qualityColor = computed(() => latest.value ? (QUALITY_COLORS[latest.value.sensorQuality] || '#90a4ae') : '#90a4ae')

function signed(v) {
  const val = Number(v || 0).toFixed(1)
  return val >= 0 ? `+${val}` : val
}
</script>

<style scoped>
.status-panel {
  padding: 16px 20px;
  background: linear-gradient(135deg, rgba(30, 41, 59, 0.6), rgba(15, 23, 42, 0.8));
  border: 1px solid rgba(79, 195, 247, 0.2);
  border-radius: 12px;
  backdrop-filter: blur(8px);
}

.status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.status-indicator {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #78909c;
  animation: pulse 2s infinite;
}

.status-indicator.connected {
  background: #00e676;
  box-shadow: 0 0 10px #00e676;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

.status-label { color: #78909c; font-size: 13px; }
.status-value { color: #e0e6ed; font-size: 13px; font-weight: 500; }

.phase-row { margin-bottom: 14px; }

.phase-badge {
  display: inline-block;
  padding: 5px 14px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 600;
  color: #0a0e1a;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px 18px;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.metric-label {
  font-size: 11px;
  color: #78909c;
  letter-spacing: 0.3px;
}

.metric-value {
  font-size: 18px;
  font-weight: 700;
  color: #e0e6ed;
  font-variant-numeric: tabular-nums;
}

.metric-value.positive { color: #00e676; }
.metric-value.negative { color: #ff5252; }
.metric-value.blue { color: #4fc3f7; }
.metric-value.orange { color: #ff8a65; }
.metric-value.purple { color: #ba68c8; }
</style>
