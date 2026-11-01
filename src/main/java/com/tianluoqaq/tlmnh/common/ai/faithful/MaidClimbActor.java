package com.tianluoqaq.tlmnh.common.ai.faithful;

import com.tianluoqaq.tlmnh.common.ai.navigation.MaidNavigationActor.Point;

/** 原攀爬任务的帧内平台边界，路径写入仍须核对当前导航与策略身份 */
public interface MaidClimbActor {

    boolean activeClimbing();

    boolean onClimbable();

    Point climbFeet();

    boolean feetAreLadder();

    void centerOnBlock(Point pos);

    void setCanClimb(boolean value);

    void setShiftDown(boolean value);

    void setClimbMotion(double x, double y, double z);

    double climbMotionY();

    void lookAtClimbNode(Point pos);

    ClimbPath climbPath();

    interface ClimbPath {

        boolean done();

        int index();

        int length();

        Point node(int index);

        Point end();

        void index(int value);
    }
}
