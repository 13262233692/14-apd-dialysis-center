package com.apd.dialysis.protocol.canopen;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CanOpenFrame {

    private Instant timestamp;

    private int cobId;

    private boolean rtr;

    private int dlc;

    private byte[] data;

    public static final int MAX_DATA_LENGTH = 8;

    public enum FunctionCode {
        NMT(0x000),
        SYNC(0x080),
        TIMESTAMP(0x100),
        PDO1_TX(0x180),
        PDO1_RX(0x200),
        PDO2_TX(0x280),
        PDO2_RX(0x300),
        PDO3_TX(0x380),
        PDO3_RX(0x400),
        PDO4_TX(0x480),
        PDO4_RX(0x500),
        SDO_TX(0x580),
        SDO_RX(0x600),
        HEARTBEAT(0x700);

        private final int baseId;

        FunctionCode(int baseId) {
            this.baseId = baseId;
        }

        public int getBaseId() {
            return baseId;
        }

        public int withNodeId(int nodeId) {
            return baseId | (nodeId & 0x7F);
        }

        public static FunctionCode fromCobId(int cobId) {
            int functionPart = cobId & 0x780;
            for (FunctionCode fc : values()) {
                if (fc.baseId == functionPart) {
                    return fc;
                }
            }
            return null;
        }
    }

    public FunctionCode getFunctionCode() {
        return FunctionCode.fromCobId(cobId);
    }

    public int getNodeId() {
        return cobId & 0x7F;
    }

    public ByteBuffer toByteBuffer() {
        ByteBuffer buf = ByteBuffer.allocate(13);
        buf.putInt(cobId);
        buf.put((byte) (rtr ? 1 : 0));
        buf.put((byte) dlc);
        buf.put(data != null ? data : new byte[0]);
        buf.flip();
        return buf;
    }

    public static CanOpenFrame fromByteBuffer(ByteBuffer buf) {
        if (buf.remaining() < 6) {
            throw new IllegalArgumentException("Insufficient bytes for CANopen frame");
        }
        int cobId = buf.getInt();
        boolean rtr = buf.get() != 0;
        int dlc = buf.get() & 0xFF;
        dlc = Math.min(dlc, MAX_DATA_LENGTH);
        byte[] data = new byte[dlc];
        if (dlc > 0 && buf.remaining() >= dlc) {
            buf.get(data);
        }
        return CanOpenFrame.builder()
                .timestamp(Instant.now())
                .cobId(cobId)
                .rtr(rtr)
                .dlc(dlc)
                .data(data)
                .build();
    }

    public String toHexString() {
        StringBuilder sb = new StringBuilder();
        if (data != null) {
            for (int i = 0; i < data.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format("%02X", data[i] & 0xFF));
            }
        }
        return String.format("COB-ID=0x%03X, RTR=%s, DLC=%d, DATA=[%s]",
                cobId, rtr ? "Y" : "N", dlc, sb.toString());
    }
}
