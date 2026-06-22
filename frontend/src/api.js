import axios from 'axios'
import { API_BASE } from './config.js'

const api = axios.create({
  baseURL: API_BASE,
  timeout: 10000
})

export const dialysisApi = {
  getStatus() {
    return api.get('/dialysis/status').then(r => r.data)
  },
  getLatest() {
    return api.get('/dialysis/datapoint/latest').then(r => r.data)
  },
  getHistory(limit = 100) {
    return api.get(`/dialysis/datapoint/history?limit=${limit}`).then(r => r.data)
  },
  getDevices() {
    return api.get('/dialysis/devices').then(r => r.data)
  },
  getDevice(nodeId) {
    return api.get(`/dialysis/devices/${nodeId}`).then(r => r.data)
  },
  sendCommand(type, nodeId = 1, targetValue = 0) {
    return api.post('/dialysis/command', {
      type,
      nodeId,
      targetValue
    }).then(r => r.data)
  },
  tareSensors() {
    return api.post('/dialysis/tare').then(r => r.data)
  },
  health() {
    return api.get('/system/health').then(r => r.data)
  },
  getTurbidityAlert() {
    return api.get('/turbidity/alert/current').then(r => r.data).catch(() => null)
  },
  acknowledgeAlert(alertId) {
    return api.post(`/turbidity/alert/${alertId}/acknowledge`).then(r => r.data)
  },
  dismissAlert() {
    return api.post('/turbidity/alert/dismiss').then(r => r.data)
  },
  getTurbidityHistory72h() {
    return api.get('/turbidity/history/72h').then(r => r.data).catch(() => [])
  },
  getDrainFlowHistory() {
    return api.get('/turbidity/history/flow').then(r => r.data).catch(() => [])
  }
}
