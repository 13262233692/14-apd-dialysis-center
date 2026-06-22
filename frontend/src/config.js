export const API_BASE = '/api'
export const WS_URL = `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/api/ws/dialysis`
export const WS_HTTP_URL = `${window.location.protocol}//${window.location.host}/api/ws/dialysis`

export const PHASE_LABELS = {
  IDLE: '待机',
  FILL: '灌入',
  DWELL: '留置',
  DRAIN: '引流',
  COMPLETE: '完成'
}

export const QUALITY_LABELS = {
  EXCELLENT: '优秀',
  GOOD: '良好',
  FAIR: '一般',
  POOR: '较差',
  NOISY: '噪声大'
}

export const QUALITY_COLORS = {
  EXCELLENT: '#00ff88',
  GOOD: '#4fc3f7',
  FAIR: '#ffb74d',
  POOR: '#ff7043',
  NOISY: '#ef5350'
}

export const PHASE_COLORS = {
  IDLE: '#90a4ae',
  FILL: '#4fc3f7',
  DWELL: '#ba68c8',
  DRAIN: '#ff8a65',
  COMPLETE: '#81c784'
}
