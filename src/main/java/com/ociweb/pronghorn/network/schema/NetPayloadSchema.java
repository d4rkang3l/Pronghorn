package com.ociweb.pronghorn.network.schema;

import com.ociweb.pronghorn.pipe.FieldReferenceOffsetManager;
import com.ociweb.pronghorn.pipe.MessageSchema;

public class NetPayloadSchema extends MessageSchema {

	public final static FieldReferenceOffsetManager FROM = new FieldReferenceOffsetManager(
		    new int[]{0xc0400004,0x90000000,0x90000001,0xb8000000,0xc0200004,0xc0400005,0x90000000,0x90000001,0x90000002,0xb8000001,0xc0200005,0xc0400002,0x90000000,0xc0200002,0xc0400003,0x90000000,0x80000000,0xc0200003,0xc0400002,0x88000001,0xc0200002},
		    (short)0,
		    new String[]{"Encrypted","ConnectionId","ArrivalTime","Payload",null,"Plain","ConnectionId","ArrivalTime","Position","Payload",null,"Disconnect","ConnectionId",null,"Upgrade","ConnectionId","NewRoute",null,"Begin","SequnceNo",null},
		    new long[]{200, 201, 210, 203, 0, 210, 201, 210, 206, 204, 0, 203, 201, 0, 307, 201, 205, 0, 208, 209, 0},
		    new String[]{"global",null,null,null,null,"global",null,null,null,null,null,"global",null,null,"global",null,null,null,"global",null,null},
		    "NetPayload.xml",
		    new long[]{2, 2, 0},
		    new int[]{2, 2, 0});
    
    public static final NetPayloadSchema instance = new NetPayloadSchema();
    
    public static final int MSG_ENCRYPTED_200 = 0x00000000;
    public static final int MSG_ENCRYPTED_200_FIELD_CONNECTIONID_201 = 0x00800001;
    public static final int MSG_ENCRYPTED_200_FIELD_ARRIVALTIME_210 = 0x00800003;
    public static final int MSG_ENCRYPTED_200_FIELD_PAYLOAD_203 = 0x01c00005;
    
    public static final int MSG_PLAIN_210 = 0x00000005;
    public static final int MSG_PLAIN_210_FIELD_CONNECTIONID_201 = 0x00800001;
    public static final int MSG_PLAIN_210_FIELD_ARRIVALTIME_210 = 0x00800003;
    public static final int MSG_PLAIN_210_FIELD_POSITION_206 = 0x00800005;
    public static final int MSG_PLAIN_210_FIELD_PAYLOAD_204 = 0x01c00007;
    
    public static final int MSG_DISCONNECT_203 = 0x0000000b;  //NOTE: must NOT use var length field, we are accumulating for plain writes
    public static final int MSG_DISCONNECT_203_FIELD_CONNECTIONID_201 = 0x00800001;
    
    public static final int MSG_UPGRADE_307 = 0x0000000e;  //NOTE: must NOT use var length field, we are accumulating for plain writes
    public static final int MSG_UPGRADE_307_FIELD_CONNECTIONID_201 = 0x00800001;
    public static final int MSG_UPGRADE_307_FIELD_NEWROUTE_205 = 0x00000003;
    
    public static final int MSG_BEGIN_208 = 0x00000012;
    public static final int MSG_BEGIN_208_FIELD_SEQUNCENO_209 = 0x00400001;
    
    
    protected NetPayloadSchema() {
        super(FROM);
    }
        
}
