package info.nightscout.androidaps.plugins.pump.insight.utils;

import org.junit.Test;

public class ByteBufTest {

    @Test
    public void testUintConversion() {
        for(int i=0; i<65535; i++) {
            double floatNum = ByteBuf.fromUInt16X100(i);
            int roundTrip = ByteBuf.toUint16X100(floatNum);
            System.err.println("org "+i+" float "+floatNum+" back "+roundTrip);
        }
    }
}
