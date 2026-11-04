package com.tianluoqaq.tlmnh.common.entity;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

public class MaidPlayerCarryPositionTest {

    @Test
    public void originalTlmSeatUsesFeetAndBodyDirectionForLocalAndRemoteCarriers() {
        var remote = EntityMaid.playerCarryPosition(4, 122, -12, 0, 0, false);
        var local = EntityMaid.playerCarryPosition(4, 122 + (double) 1.62F, -12, 1.62F, 0, false);
        assertEquals(4, remote.xCoord, .00001);
        assertEquals(122.15, remote.yCoord, .00001);
        assertEquals(-11.25, remote.zCoord, .00001);
        assertEquals(remote.xCoord, local.xCoord, .00001);
        assertEquals(remote.yCoord, local.yCoord, .00001);
        assertEquals(remote.zCoord, local.zCoord, .00001);
        var turned = EntityMaid.playerCarryPosition(4, 122, -12, 0, 90, true);
        assertEquals(3.25, turned.xCoord, .0001);
        assertEquals(121.85, turned.yCoord, .00001);
        assertEquals(-12, turned.zCoord, .0001);
    }
}
