package com.apd.dialysis.protocol.canopen;

public class SdoProtocol {

    public static final int SDO_RX_BASE = 0x600;
    public static final int SDO_TX_BASE = 0x580;

    public static final byte CMD_UPLOAD_INITIATE = 0x40;
    public static final byte CMD_UPLOAD_SEGMENT = 0x60;
    public static final byte CMD_DOWNLOAD_INITIATE_EXPEDITED = 0x23;
    public static final byte CMD_DOWNLOAD_INITIATE_NORMAL = 0x21;
    public static final byte CMD_DOWNLOAD_SEGMENT = 0x00;
    public static final byte CMD_ABORT = (byte) 0x80;

    public static class SdoMessage {
        public int nodeId;
        public boolean isResponse;
        public int index;
        public int subIndex;
        public byte[] data;
        public boolean expedited;
        public boolean complete;
        public int abortCode;

        public boolean isAbort() {
            return abortCode != 0;
        }
    }

    public static CanOpenFrame buildSdoDownload(int nodeId, int index, int subIndex, int value) {
        byte[] data = new byte[8];
        data[0] = CMD_DOWNLOAD_INITIATE_EXPEDITED;
        data[1] = (byte) (index & 0xFF);
        data[2] = (byte) ((index >> 8) & 0xFF);
        data[3] = (byte) (subIndex & 0xFF);
        data[4] = (byte) (value & 0xFF);
        data[5] = (byte) ((value >> 8) & 0xFF);
        data[6] = (byte) ((value >> 16) & 0xFF);
        data[7] = (byte) ((value >> 24) & 0xFF);

        return CanOpenFrame.builder()
                .cobId(SDO_RX_BASE | (nodeId & 0x7F))
                .rtr(false)
                .dlc(8)
                .data(data)
                .build();
    }

    public static CanOpenFrame buildSdoUpload(int nodeId, int index, int subIndex) {
        byte[] data = new byte[8];
        data[0] = CMD_UPLOAD_INITIATE;
        data[1] = (byte) (index & 0xFF);
        data[2] = (byte) ((index >> 8) & 0xFF);
        data[3] = (byte) (subIndex & 0xFF);

        return CanOpenFrame.builder()
                .cobId(SDO_RX_BASE | (nodeId & 0x7F))
                .rtr(false)
                .dlc(8)
                .data(data)
                .build();
    }

    public static SdoMessage parseSdoFrame(CanOpenFrame frame) {
        SdoMessage msg = new SdoMessage();
        int cobId = frame.getCobId();
        msg.nodeId = cobId & 0x7F;
        msg.isResponse = (cobId & 0x780) == SDO_TX_BASE;

        byte[] data = frame.getData();
        if (data == null || data.length < 4) {
            return msg;
        }

        byte cmd = data[0];
        msg.index = (data[1] & 0xFF) | ((data[2] & 0xFF) << 8);
        msg.subIndex = data[3] & 0xFF;

        if ((cmd & CMD_ABORT) != 0) {
            msg.abortCode = (data[4] & 0xFF)
                    | ((data[5] & 0xFF) << 8)
                    | ((data[6] & 0xFF) << 16)
                    | ((data[7] & 0xFF) << 24);
            return msg;
        }

        if ((cmd & 0xE0) == 0x20) {
            msg.expedited = (cmd & 0x02) != 0;
            msg.complete = (cmd & 0x01) != 0;
            if (msg.expedited && data.length >= 8) {
                int n = 4 - ((cmd >> 2) & 0x03);
                msg.data = new byte[n];
                System.arraycopy(data, 4, msg.data, 0, n);
            }
        } else if ((cmd & 0xE0) == 0x40) {
            msg.expedited = (cmd & 0x02) != 0;
            msg.complete = true;
            if (data.length >= 8) {
                int n = 4 - ((cmd >> 2) & 0x03);
                msg.data = new byte[n];
                System.arraycopy(data, 4, msg.data, 0, n);
            }
        } else if ((cmd & 0xE0) == 0x60) {
            msg.complete = (cmd & 0x01) != 0;
        }

        return msg;
    }

    public static int sdoDataToInt(byte[] data) {
        if (data == null) return 0;
        int value = 0;
        for (int i = 0; i < data.length && i < 4; i++) {
            value |= (data[i] & 0xFF) << (i * 8);
        }
        return value;
    }

    public static double sdoDataToDouble(byte[] data, int scale) {
        return sdoDataToInt(data) / Math.pow(10.0, scale);
    }
}
