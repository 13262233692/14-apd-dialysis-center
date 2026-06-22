<template>
  <Teleport to="body">
    <Transition name="modal-fade">
      <div v-if="show" class="modal-mask" @click.self>
        <div class="modal-container">
          <div class="modal-header">
            <div class="header-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <path d="M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"
                  stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <div class="header-title">
              <h2>腹膜炎可疑 · 必须停机确诊</h2>
              <p>检测到引流液浑浊度异常，高度提示细菌性腹膜炎早期表现</p>
            </div>
            <div class="header-level">
              <span class="level-badge critical">危急 CRITICAL</span>
            </div>
          </div>

          <div class="modal-body">
            <div class="evidence-section">
              <h3>诊断依据</h3>
              <ul class="evidence-list">
                <li v-for="(e, i) in alert?.evidence || []" :key="i">
                  <span class="dot"></span>{{ e }}
                </li>
              </ul>
            </div>

            <div class="charts-section">
              <div class="chart-block">
                <div class="chart-title">光学吸收光谱 (420 / 540 / 660 / 720 nm)</div>
                <div ref="spectrumChartRef" class="chart-canvas"></div>
              </div>
              <div class="chart-block">
                <div class="chart-title">引流液透光率趋势 (近120数据点)</div>
                <div ref="transmittanceChartRef" class="chart-canvas"></div>
              </div>
            </div>

            <div class="action-section">
              <div class="recommend">
                <strong>推荐操作：</strong>
                <span>{{ alert?.recommendedAction || '请立即停机并联系医护人员' }}</span>
              </div>
            </div>
          </div>

          <div class="modal-footer">
            <button class="btn btn-danger" @click="handleEmergencyStop">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" width="18" height="18">
                <path d="M6 6l12 12M6 18L18 6" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              紧急停机
            </button>
            <button class="btn btn-primary" @click="handleAcknowledge" :disabled="acknowledged">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" width="18" height="18">
                <path d="M5 13l4 4L19 7" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              {{ acknowledged ? '已确认' : '我已知晓并确认' }}
            </button>
            <button v-if="acknowledged" class="btn btn-ghost" @click="handleDismiss">
              关闭
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { dialysisApi } from '../api.js'
import { acknowledgePeritonitisAlert, dismissPeritonitisAlert } from '../store.js'

const props = defineProps({
  show: { type: Boolean, default: false },
  alert: { type: Object, default: null },
  acknowledged: { type: Boolean, default: false }
})

const emit = defineEmits(['emergency-stop'])

const spectrumChartRef = ref(null)
const transmittanceChartRef = ref(null)
let spectrumChart = null
let transmittanceChart = null

function buildSpectrumOption(alert) {
  const history = alert?.spectralHistory72h || []
  const latest = history.length > 0 ? history[history.length - 1] : null
  const baseline = history.length > 10 ? history.slice(0, 20).reduce((s, h) => s + (h?.transmittancePercent || 0), 0) / Math.min(20, history.length) : 96
  const latestA = latest ? {
    420: latest.absorbance420nm || 0.6,
    540: latest.absorbance540nm || 0.48,
    660: latest.absorbance660nm || 0.35,
    720: latest.absorbance720nm || 0.28
  } : { 420: 0.6, 540: 0.48, 660: 0.35, 720: 0.28 }
  const baselineA = {
    420: 0.04, 540: 0.03, 660: 0.02, 720: 0.02
  }
  const wavelengths = [420, 540, 660, 720]
  return {
    backgroundColor: 'transparent',
    grid: { left: 50, right: 20, top: 30, bottom: 35 },
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(15,23,42,0.95)', borderColor: '#ef4444', textStyle: { color: '#fff' } },
    legend: { data: ['当前吸收', '基线参考'], textStyle: { color: '#e0e6ed' }, top: 0, right: 10 },
    xAxis: {
      type: 'category',
      data: wavelengths.map(w => `${w}nm`),
      axisLabel: { color: '#94a3b8' },
      axisLine: { lineStyle: { color: '#334155' } }
    },
    yAxis: {
      type: 'value',
      name: '吸光度',
      nameTextStyle: { color: '#94a3b8' },
      axisLabel: { color: '#94a3b8' },
      axisLine: { lineStyle: { color: '#334155' } },
      splitLine: { lineStyle: { color: 'rgba(148,163,184,0.1)' } }
    },
    series: [
      {
        name: '当前吸收',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 8,
        data: wavelengths.map(w => latestA[w]),
        lineStyle: { color: '#ef4444', width: 3 },
        itemStyle: { color: '#ef4444', borderColor: '#fff', borderWidth: 2 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(239,68,68,0.55)' },
            { offset: 1, color: 'rgba(239,68,68,0.02)' }
          ])
        }
      },
      {
        name: '基线参考',
        type: 'line',
        smooth: true,
        symbol: 'diamond',
        symbolSize: 6,
        data: wavelengths.map(w => baselineA[w]),
        lineStyle: { color: '#4ade80', width: 2, type: 'dashed' },
        itemStyle: { color: '#4ade80' }
      }
    ]
  }
}

function buildTransmittanceOption(alert) {
  const history = alert?.spectralHistory72h || []
  const recent = history.slice(-120)
  const times = recent.map((_, i) => `t-${recent.length - i}`)
  const values = recent.map(h => h?.transmittancePercent ?? 0)
  const times2 = alert?.drainFlowHistory?.slice(-120) || []
  return {
    backgroundColor: 'transparent',
    grid: { left: 50, right: 55, top: 30, bottom: 35 },
    tooltip: { trigger: 'axis', backgroundColor: 'rgba(15,23,42,0.95)', borderColor: '#ef4444', textStyle: { color: '#fff' } },
    legend: { data: ['透光率 %', '引流流速 mL/min'], textStyle: { color: '#e0e6ed' }, top: 0, right: 10 },
    xAxis: {
      type: 'category',
      data: times,
      axisLabel: { color: '#94a3b8', interval: Math.floor(times.length / 8) },
      axisLine: { lineStyle: { color: '#334155' } }
    },
    yAxis: [
      {
        type: 'value',
        name: '透光率%',
        min: 0, max: 100,
        nameTextStyle: { color: '#94a3b8' },
        axisLabel: { color: '#94a3b8' },
        axisLine: { lineStyle: { color: '#334155' } },
        splitLine: { lineStyle: { color: 'rgba(148,163,184,0.1)' } }
      },
      {
        type: 'value',
        name: '流速',
        nameTextStyle: { color: '#94a3b8' },
        axisLabel: { color: '#94a3b8' },
        axisLine: { lineStyle: { color: '#334155' } },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '透光率 %',
        type: 'line',
        smooth: true,
        showSymbol: false,
        data: values,
        lineStyle: { color: '#ef4444', width: 2.5 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(239,68,68,0.45)' },
            { offset: 1, color: 'rgba(239,68,68,0.02)' }
          ])
        },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: '#4ade80', type: 'dashed' },
          data: [{ yAxis: 90, label: { formatter: '正常阈值 90%', color: '#4ade80', position: 'insideEndTop' } }]
        }
      },
      {
        name: '引流流速 mL/min',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        showSymbol: false,
        data: times2.slice(-values.length),
        lineStyle: { color: '#60a5fa', width: 2 }
      }
    ]
  }
}

function initCharts() {
  if (!spectrumChartRef.value || !transmittanceChartRef.value) return
  destroyCharts()
  spectrumChart = echarts.init(spectrumChartRef.value, null, { renderer: 'canvas' })
  transmittanceChart = echarts.init(transmittanceChartRef.value, null, { renderer: 'canvas' })
  spectrumChart.setOption(buildSpectrumOption(props.alert))
  transmittanceChart.setOption(buildTransmittanceOption(props.alert))
}

function destroyCharts() {
  if (spectrumChart) { spectrumChart.dispose(); spectrumChart = null }
  if (transmittanceChart) { transmittanceChart.dispose(); transmittanceChart = null }
}

async function handleAcknowledge() {
  try {
    if (props.alert?.alertId) {
      await dialysisApi.acknowledgeAlert(props.alert.alertId)
    }
  } catch (e) { /* ignore */ }
  acknowledgePeritonitisAlert()
}

function handleDismiss() {
  try { dialysisApi.dismissAlert() } catch (e) {}
  dismissPeritonitisAlert()
}

async function handleEmergencyStop() {
  try {
    await dialysisApi.sendCommand('EMERGENCY_STOP')
  } catch (e) { /* ignore */ }
  emit('emergency-stop')
}

watch(() => props.show, (v) => {
  if (v) {
    nextTick(() => {
      setTimeout(initCharts, 150)
    })
  } else {
    destroyCharts()
  }
})

onBeforeUnmount(destroyCharts)

if (typeof window !== 'undefined') {
  window.addEventListener('resize', () => {
    if (spectrumChart) spectrumChart.resize()
    if (transmittanceChart) transmittanceChart.resize()
  })
}
</script>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(5, 8, 15, 0.85);
  backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  padding: 20px;
}

.modal-container {
  width: min(900px, 96vw);
  max-height: 92vh;
  background: linear-gradient(180deg, #1e1118 0%, #0f0a14 100%);
  border: 2px solid #dc2626;
  border-radius: 18px;
  box-shadow:
    0 0 0 3px rgba(239, 68, 68, 0.25),
    0 30px 80px rgba(0, 0, 0, 0.8),
    0 0 100px rgba(239, 68, 68, 0.25);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  animation: modalIn 0.4s cubic-bezier(0.34, 1.56, 0.64, 1);
}

@keyframes modalIn {
  from { opacity: 0; transform: scale(0.9) translateY(20px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

.modal-header {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 24px;
  background: linear-gradient(90deg, rgba(220, 38, 38, 0.35), rgba(220, 38, 38, 0.08));
  border-bottom: 1px solid rgba(239, 68, 68, 0.35);
}

.header-icon {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: linear-gradient(135deg, #dc2626, #991b1b);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  animation: iconPulse 0.9s ease-in-out infinite;
  flex-shrink: 0;
}

.header-icon svg { width: 28px; height: 28px; }

@keyframes iconPulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.6); }
  50% { box-shadow: 0 0 0 12px rgba(239, 68, 68, 0); }
}

.header-title h2 {
  font-size: 20px;
  color: #fecaca;
  font-weight: 800;
  letter-spacing: 1px;
}

.header-title p {
  font-size: 12px;
  color: #fca5a5;
  margin-top: 3px;
}

.header-level { margin-left: auto; }

.level-badge.critical {
  display: inline-block;
  padding: 6px 14px;
  background: linear-gradient(135deg, #dc2626, #7f1d1d);
  color: #fff;
  font-weight: 800;
  font-size: 12px;
  letter-spacing: 1.5px;
  border-radius: 8px;
  border: 1px solid #ef4444;
}

.modal-body {
  padding: 18px 24px;
  overflow-y: auto;
  flex: 1;
}

.evidence-section { margin-bottom: 18px; }
.evidence-section h3 {
  font-size: 13px;
  color: #fca5a5;
  text-transform: uppercase;
  letter-spacing: 1.5px;
  margin-bottom: 10px;
  font-weight: 700;
}

.evidence-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.evidence-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 14px;
  background: rgba(239, 68, 68, 0.08);
  border-left: 3px solid #ef4444;
  border-radius: 6px;
  font-size: 13px;
  color: #fee2e2;
}

.evidence-list .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #ef4444;
  flex-shrink: 0;
  animation: blink 1s ease-in-out infinite;
}

@keyframes blink { 50% { opacity: 0.3; } }

.charts-section {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-bottom: 16px;
}

.chart-block {
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(239, 68, 68, 0.2);
  border-radius: 10px;
  padding: 10px;
}

.chart-title {
  font-size: 12px;
  color: #f87171;
  font-weight: 600;
  margin-bottom: 6px;
  letter-spacing: 0.5px;
}

.chart-canvas { width: 100%; height: 200px; }

.action-section {
  padding: 12px 16px;
  background: rgba(220, 38, 38, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.3);
  border-radius: 10px;
  margin-bottom: 4px;
}

.recommend { font-size: 13px; color: #fecaca; line-height: 1.7; }
.recommend strong { color: #f87171; margin-right: 6px; }

.modal-footer {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
  padding: 16px 24px;
  border-top: 1px solid rgba(239, 68, 68, 0.25);
  background: rgba(127, 29, 29, 0.2);
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 22px;
  border: none;
  border-radius: 10px;
  font-weight: 700;
  font-size: 14px;
  cursor: pointer;
  transition: all 0.2s ease;
  letter-spacing: 0.5px;
}

.btn-danger {
  background: linear-gradient(135deg, #dc2626, #7f1d1d);
  color: #fff;
  border: 1px solid #ef4444;
  box-shadow: 0 4px 14px rgba(220, 38, 38, 0.4);
}

.btn-danger:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 6px 22px rgba(220, 38, 38, 0.6);
}

.btn-primary {
  background: linear-gradient(135deg, #2563eb, #1d4ed8);
  color: #fff;
  border: 1px solid #3b82f6;
  box-shadow: 0 4px 14px rgba(37, 99, 235, 0.35);
}

.btn-primary:hover:not(:disabled) {
  transform: translateY(-1px);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-ghost {
  background: rgba(148, 163, 184, 0.1);
  color: #cbd5e1;
  border: 1px solid rgba(148, 163, 184, 0.3);
}

.btn-ghost:hover {
  background: rgba(148, 163, 184, 0.2);
}

.modal-fade-enter-active, .modal-fade-leave-active {
  transition: opacity 0.3s ease;
}

.modal-fade-enter-active .modal-container,
.modal-fade-leave-active .modal-container {
  transition: all 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.modal-fade-enter-from, .modal-fade-leave-to { opacity: 0; }
.modal-fade-enter-from .modal-container,
.modal-fade-leave-to .modal-container {
  transform: scale(0.9) translateY(20px);
  opacity: 0;
}
</style>
