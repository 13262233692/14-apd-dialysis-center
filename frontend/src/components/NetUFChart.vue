<template>
  <div ref="chartRef" class="netuf-chart"></div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, computed } from 'vue'
import * as echarts from 'echarts'
import { dialysisStore } from '../store.js'
import { PHASE_COLORS, PHASE_LABELS } from '../config.js'

const chartRef = ref(null)
let chart = null
let resizeObserver = null

const timeLabels = computed(() =>
  dialysisStore.history.map(p => {
    const d = new Date(p.timestamp)
    return d.toLocaleTimeString('zh-CN', { hour12: false })
  })
)

const inflowData = computed(() =>
  dialysisStore.history.map(p => p.inflowVolumeMl)
)

const outflowData = computed(() =>
  dialysisStore.history.map(p => p.outflowVolumeMl)
)

const abdominalData = computed(() =>
  dialysisStore.history.map(p => p.abdominalVolumeMl)
)

const netUFData = computed(() =>
  dialysisStore.history.map(p => p.netUltrafiltrationMl)
)

function buildOption() {
  return {
    backgroundColor: 'transparent',
    animation: false,
    textStyle: { color: '#b0bec5', fontFamily: 'inherit' },
    title: {
      text: '超滤体积净平衡 (Net Ultrafiltration)',
      subtext: '实时面积图 · 腹膜转运特性监测',
      left: 'center',
      top: 8,
      textStyle: { color: '#e0e6ed', fontSize: 16, fontWeight: 600 },
      subtextStyle: { color: '#78909c', fontSize: 12 }
    },
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(20, 28, 48, 0.95)',
      borderColor: '#3949ab',
      borderWidth: 1,
      textStyle: { color: '#e0e6ed' },
      axisPointer: { type: 'cross', label: { backgroundColor: '#3949ab' } },
      formatter: function (params) {
        if (!params || !params.length) return ''
        let html = `<div style="font-weight:600;margin-bottom:6px;">${params[0].axisValue}</div>`
        params.forEach(p => {
          html += `<div style="display:flex;align-items:center;gap:6px;">
            <span style="width:10px;height:10px;border-radius:50%;background:${p.color};display:inline-block;"></span>
            <span>${p.seriesName}:</span>
            <span style="font-weight:600;color:${p.color};margin-left:auto;">${p.value.toFixed(1)} ml</span>
          </div>`
        })
        const latest = dialysisStore.latest
        if (latest) {
          html += `<div style="margin-top:8px;padding-top:8px;border-top:1px solid rgba(255,255,255,0.1);">
            <div>阶段: <span style="color:${PHASE_COLORS[latest.phase] || '#fff'};">${PHASE_LABELS[latest.phase] || latest.phase}</span></div>
            <div>温度: ${latest.dialysateTemperatureC.toFixed(1)} °C</div>
          </div>`
        }
        return html
      }
    },
    legend: {
      data: ['灌入量', '引流袋量', '腹腔留置量', '净超滤量'],
      top: 50,
      left: 'center',
      textStyle: { color: '#90a4ae' },
      icon: 'roundRect'
    },
    grid: {
      left: 60,
      right: 40,
      top: 100,
      bottom: 40
    },
    xAxis: {
      type: 'category',
      data: timeLabels.value,
      boundaryGap: false,
      axisLine: { lineStyle: { color: '#37474f' } },
      axisLabel: { color: '#78909c', fontSize: 10 },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value',
      name: '体积 (ml)',
      nameTextStyle: { color: '#78909c', padding: [0, 0, 0, 40] },
      axisLine: { show: false },
      axisLabel: { color: '#78909c' },
      splitLine: { lineStyle: { color: 'rgba(55, 71, 79, 0.5)', type: 'dashed' } }
    },
    dataZoom: [
      {
        type: 'inside',
        start: 0,
        end: 100,
        zoomLock: false
      }
    ],
    series: [
      {
        name: '灌入量',
        type: 'line',
        smooth: true,
        symbol: 'none',
        data: inflowData.value,
        lineStyle: { width: 2, color: '#4fc3f7' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(79, 195, 247, 0.55)' },
            { offset: 1, color: 'rgba(79, 195, 247, 0.02)' }
          ])
        }
      },
      {
        name: '引流袋量',
        type: 'line',
        smooth: true,
        symbol: 'none',
        data: outflowData.value,
        lineStyle: { width: 2, color: '#ff8a65' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(255, 138, 101, 0.5)' },
            { offset: 1, color: 'rgba(255, 138, 101, 0.02)' }
          ])
        }
      },
      {
        name: '腹腔留置量',
        type: 'line',
        smooth: true,
        symbol: 'none',
        data: abdominalData.value,
        lineStyle: { width: 2.5, color: '#ba68c8' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(186, 104, 200, 0.45)' },
            { offset: 1, color: 'rgba(186, 104, 200, 0.02)' }
          ])
        }
      },
      {
        name: '净超滤量',
        type: 'line',
        smooth: true,
        symbol: 'none',
        data: netUFData.value,
        lineStyle: { width: 3, color: '#00e676' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(0, 230, 118, 0.4)' },
            { offset: 0.5, color: 'rgba(0, 230, 118, 0.05)' },
            { offset: 1, color: 'rgba(255, 82, 82, 0.35)' }
          ])
        },
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: '#ff5252', type: 'dashed', width: 1 },
          data: [{ yAxis: 0, label: { formatter: '零净超滤基线', color: '#ff5252', position: 'insideEndTop' } }]
        }
      }
    ]
  }
}

function renderChart() {
  if (!chart) return
  chart.setOption(buildOption(), { notMerge: false, lazyUpdate: true })
}

onMounted(() => {
  chart = echarts.init(chartRef.value)
  renderChart()

  resizeObserver = new ResizeObserver(() => chart && chart.resize())
  resizeObserver.observe(chartRef.value)

  watch(
    () => dialysisStore.history.length,
    () => renderChart()
  )
})

onBeforeUnmount(() => {
  if (resizeObserver) resizeObserver.disconnect()
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
.netuf-chart {
  width: 100%;
  height: 100%;
  min-height: 360px;
}
</style>
