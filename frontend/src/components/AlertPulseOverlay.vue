<template>
  <Transition name="pulse-fade">
    <div v-if="show" class="alert-pulse-overlay" :class="{ 'is-critical': isCritical }">
      <div class="pulse-border"></div>
      <div class="pulse-content">
        <div class="pulse-icon">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <path d="M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"
              stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
        </div>
        <div class="pulse-text">
          <div class="pulse-title">腹膜炎可疑 · 最高级别警报</div>
          <div class="pulse-subtitle">请立即停机确诊！引流液浑浊度异常</div>
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
defineProps({
  show: { type: Boolean, default: false },
  isCritical: { type: Boolean, default: true }
})
</script>

<style scoped>
.alert-pulse-overlay {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 9998;
  background:
    repeating-linear-gradient(
      45deg,
      rgba(239, 68, 68, 0.0) 0px,
      rgba(239, 68, 68, 0.0) 30px,
      rgba(239, 68, 68, 0.08) 30px,
      rgba(239, 68, 68, 0.08) 60px
    );
  animation: bgPulse 1.2s ease-in-out infinite;
}

.pulse-border {
  position: absolute;
  inset: 0;
  border: 6px solid transparent;
  box-shadow:
    inset 0 0 60px rgba(239, 68, 68, 0.35),
    0 0 120px rgba(239, 68, 68, 0.5);
  animation: borderPulse 0.9s ease-in-out infinite;
}

.pulse-content {
  position: absolute;
  top: 18px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 28px;
  background: linear-gradient(135deg, rgba(220, 38, 38, 0.95), rgba(153, 27, 27, 0.95));
  border: 2px solid #fca5a5;
  border-radius: 14px;
  box-shadow:
    0 10px 40px rgba(220, 38, 38, 0.6),
    0 0 60px rgba(239, 68, 68, 0.4);
  animation: barPulse 0.9s ease-in-out infinite;
}

.pulse-icon {
  width: 38px;
  height: 38px;
  color: #fff;
  animation: iconShake 0.5s ease-in-out infinite;
}

.pulse-icon svg {
  width: 100%;
  height: 100%;
}

.pulse-text .pulse-title {
  font-size: 18px;
  font-weight: 800;
  color: #fff;
  letter-spacing: 2px;
  text-shadow: 0 0 8px rgba(0,0,0,0.5);
}

.pulse-text .pulse-subtitle {
  font-size: 12px;
  color: #fecaca;
  margin-top: 3px;
  letter-spacing: 0.5px;
}

@keyframes bgPulse {
  0%, 100% { background-color: rgba(239, 68, 68, 0.0); }
  50% { background-color: rgba(239, 68, 68, 0.18); }
}

@keyframes borderPulse {
  0%, 100% {
    box-shadow:
      inset 0 0 60px rgba(239, 68, 68, 0.35),
      0 0 120px rgba(239, 68, 68, 0.5);
  }
  50% {
    box-shadow:
      inset 0 0 120px rgba(239, 68, 68, 0.55),
      0 0 180px rgba(239, 68, 68, 0.8);
  }
}

@keyframes barPulse {
  0%, 100% {
    transform: translateX(-50%) scale(1);
    box-shadow: 0 10px 40px rgba(220, 38, 38, 0.6), 0 0 60px rgba(239, 68, 68, 0.4);
  }
  50% {
    transform: translateX(-50%) scale(1.04);
    box-shadow: 0 10px 60px rgba(220, 38, 38, 0.9), 0 0 80px rgba(239, 68, 68, 0.7);
  }
}

@keyframes iconShake {
  0%, 100% { transform: rotate(-6deg); }
  50% { transform: rotate(6deg); }
}

.pulse-fade-enter-active, .pulse-fade-leave-active {
  transition: opacity 0.3s ease;
}
.pulse-fade-enter-from, .pulse-fade-leave-to {
  opacity: 0;
}
</style>
