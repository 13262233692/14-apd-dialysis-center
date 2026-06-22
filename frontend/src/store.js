import { Client } from '@stomp/stompjs'
import { WS_HTTP_URL } from './config.js'
import { reactive } from 'vue'

const MAX_HISTORY = 300

export const dialysisStore = reactive({
  connected: false,
  latest: null,
  history: [],
  devices: [],
  status: {
    hardwareRunning: false,
    spikeSuppressionRate: 0,
    spikesSuppressed: 0,
    bufferSize: 0
  },
  alert: {
    active: false,
    data: null,
    acknowledged: false,
    showPulse: false,
    showModal: false,
    triggeredAt: null
  }
})

let stompClient = null
let reconnectTimer = null

function onDataPoint(message) {
  try {
    const point = JSON.parse(message.body)
    dialysisStore.latest = point
    if (dialysisStore.history.length >= MAX_HISTORY) {
      dialysisStore.history.shift()
    }
    dialysisStore.history.push(point)
  } catch (e) {
    console.error('Failed to parse data point:', e)
  }
}

function onDevices(message) {
  try {
    dialysisStore.devices = JSON.parse(message.body)
  } catch (e) {
    console.error('Failed to parse devices:', e)
  }
}

function onStatus(message) {
  try {
    const s = JSON.parse(message.body)
    Object.assign(dialysisStore.status, s)
  } catch (e) {
    console.error('Failed to parse status:', e)
  }
}

function onPeritonitisAlert(message) {
  try {
    const alert = JSON.parse(message.body)
    console.error('[ALERT] 腹膜炎预警:', alert)
    dialysisStore.alert.data = alert
    dialysisStore.alert.active = true
    dialysisStore.alert.acknowledged = !!alert.acknowledged
    dialysisStore.alert.triggeredAt = alert.timestamp || new Date().toISOString()
    if (!alert.acknowledged) {
      dialysisStore.alert.showPulse = true
      dialysisStore.alert.showModal = true
      try {
        if (window.speechSynthesis) {
          const u = new SpeechSynthesisUtterance('警告，检测到腹膜炎可疑症状，请立即停机确诊')
          u.lang = 'zh-CN'
          u.volume = 1.0
          window.speechSynthesis.speak(u)
        }
      } catch (_) {}
    }
  } catch (e) {
    console.error('Failed to parse alert:', e)
  }
}

export function connectWebSocket() {
  if (stompClient && stompClient.connected) {
    return
  }
  disconnectWebSocket()
  const brokerUrl = WS_HTTP_URL
  stompClient = new Client({
    brokerURL: brokerUrl,
    reconnectDelay: 3000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    debug: () => {},
    onConnect: () => {
      dialysisStore.connected = true
      console.log('[WS] Connected to dialysis stream')
      stompClient.subscribe('/topic/dialysis/datapoint', onDataPoint)
      stompClient.subscribe('/topic/dialysis/devices', onDevices)
      stompClient.subscribe('/topic/dialysis/status', onStatus)
      stompClient.subscribe('/topic/dialysis/alerts/peritonitis', onPeritonitisAlert)
    },
    onDisconnect: () => {
      dialysisStore.connected = false
      console.log('[WS] Disconnected')
    },
    onWebSocketError: (error) => {
      dialysisStore.connected = false
      console.warn('[WS] WebSocket error:', error)
    },
    onStompError: (frame) => {
      console.error('[WS] STOMP error:', frame.headers['message'])
    }
  })
  try {
    stompClient.activate()
  } catch (e) {
    console.error('[WS] Failed to activate:', e)
  }
}

export function disconnectWebSocket() {
  if (stompClient) {
    try {
      stompClient.deactivate()
    } catch (e) {}
    stompClient = null
  }
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  dialysisStore.connected = false
}

export function acknowledgePeritonitisAlert() {
  dialysisStore.alert.acknowledged = true
  dialysisStore.alert.showPulse = false
}

export function dismissPeritonitisAlert() {
  dialysisStore.alert.active = false
  dialysisStore.alert.data = null
  dialysisStore.alert.acknowledged = false
  dialysisStore.alert.showPulse = false
  dialysisStore.alert.showModal = false
  dialysisStore.alert.triggeredAt = null
}

export function formatTime(isoString) {
  if (!isoString) return ''
  const d = new Date(isoString)
  return d.toLocaleTimeString('zh-CN', { hour12: false })
}

export function formatDateTime(isoString) {
  if (!isoString) return ''
  const d = new Date(isoString)
  return d.toLocaleString('zh-CN', { hour12: false })
}
