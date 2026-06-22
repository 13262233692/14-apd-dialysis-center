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
