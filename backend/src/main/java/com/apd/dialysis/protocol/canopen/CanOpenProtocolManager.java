package com.apd.dialysis.protocol.canopen;

import com.apd.dialysis.model.DeviceCommand;
import com.apd.dialysis.model.DeviceStatus;
import com.apd.dialysis.model.WeightSensorReading;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class CanOpenProtocolManager {

    private final CanOpenNioServer nioServer;

    private final CopyOnWriteArrayList<Consumer<WeightSensorReading>> sensorListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<DeviceStatus>> deviceStatusListeners = new CopyOnWriteArrayList<>();

    @PostConstruct
    public void init() {
        nioServer.addFrameListener(this::handleIncomingFrame);
    }

    private void handleIncomingFrame(CanOpenFrame frame) {
        CanOpenFrame.FunctionCode fc = frame.getFunctionCode();
        if (fc == null) return;

        try {
            if (fc == CanOpenFrame.FunctionCode.PDO1_TX || fc == CanOpenFrame.FunctionCode.PDO2_TX) {
                WeightSensorReading reading = PdoParser.parseWeightSensorPdo(frame);
                if (reading != null) {
                    notifySensorListeners(reading);
                }
            } else if (fc == CanOpenFrame.FunctionCode.PDO3_TX) {
                DeviceStatus pumpStatus = PdoParser.parsePumpPdo(frame);
                if (pumpStatus != null) {
                    notifyDeviceStatusListeners(pumpStatus);
                }
            } else if (fc == CanOpenFrame.FunctionCode.PDO4_TX) {
                DeviceStatus heaterStatus = PdoParser.parseHeaterPdo(frame);
                if (heaterStatus != null) {
                    notifyDeviceStatusListeners(heaterStatus);
                }
            } else if (fc == CanOpenFrame.FunctionCode.SDO_TX) {
                log.debug("SDO response: {}", frame.toHexString());
            } else if (fc == CanOpenFrame.FunctionCode.HEARTBEAT) {
                log.debug("Heartbeat from node {}", frame.getNodeId());
            } else {
                log.trace("Unhandled frame: {}", frame.toHexString());
            }
        } catch (Exception e) {
            log.warn("Error handling CANopen frame", e);
        }
    }

    public void sendCommand(DeviceCommand command) {
        CanOpenFrame frame = buildCommandFrame(command);
        if (frame != null) {
            nioServer.sendFrame(frame);
            log.info("Sent command: {} to node {}", command.getType(), command.getNodeId());
        }
    }

    private CanOpenFrame buildCommandFrame(DeviceCommand command) {
        int nodeId = command.getNodeId();
        DeviceCommand.CommandType type = command.getType();
        if (type == DeviceCommand.CommandType.START_PUMP) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x6040, 0x00, 0x0F);
        } else if (type == DeviceCommand.CommandType.STOP_PUMP) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x6040, 0x00, 0x06);
        } else if (type == DeviceCommand.CommandType.SET_FLOW_RATE) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x60FF, 0x00, (int) command.getTargetValue());
        } else if (type == DeviceCommand.CommandType.START_HEATING) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x2000, 0x01, 1);
        } else if (type == DeviceCommand.CommandType.STOP_HEATING) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x2000, 0x01, 0);
        } else if (type == DeviceCommand.CommandType.SET_TEMPERATURE) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x2000, 0x02, (int) (command.getTargetValue() * 10));
        } else if (type == DeviceCommand.CommandType.TARE_SENSOR) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x3000, 0x01, 1);
        } else if (type == DeviceCommand.CommandType.CALIBRATE_SENSOR) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x3000, 0x02, 1);
        } else if (type == DeviceCommand.CommandType.START_DIALYSIS) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x4000, 0x00, 1);
        } else if (type == DeviceCommand.CommandType.STOP_DIALYSIS) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x4000, 0x00, 0);
        } else if (type == DeviceCommand.CommandType.EMERGENCY_STOP) {
            return SdoProtocol.buildSdoDownload(nodeId, 0x6040, 0x00, 0x0B);
        }
        return null;
    }

    public void addSensorListener(Consumer<WeightSensorReading> listener) {
        sensorListeners.add(listener);
    }

    public void addDeviceStatusListener(Consumer<DeviceStatus> listener) {
        deviceStatusListeners.add(listener);
    }

    private void notifySensorListeners(WeightSensorReading reading) {
        for (Consumer<WeightSensorReading> listener : sensorListeners) {
            try {
                listener.accept(reading);
            } catch (Exception e) {
                log.warn("Sensor listener error", e);
            }
        }
    }

    private void notifyDeviceStatusListeners(DeviceStatus status) {
        for (Consumer<DeviceStatus> listener : deviceStatusListeners) {
            try {
                listener.accept(status);
            } catch (Exception e) {
                log.warn("Device status listener error", e);
            }
        }
    }
}
