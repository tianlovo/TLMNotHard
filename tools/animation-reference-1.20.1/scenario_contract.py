"""有界动画夹具的输入合同，不作为通用游戏轨迹协议"""

IDLE_MODE = "controlled-idle-v0"
WALK_MODE = "controlled-idle-walk-idle-v1"
HEAD_MODE = "controlled-head-turn-v1"
IDLE_CONTRACT = "controlled-empty-idle-v1"
WALK_CONTRACT = "controlled-empty-idle-walk-idle-v1"
HEAD_CONTRACT = "controlled-empty-head-turn-v1"


def head_angles(step):
    if not 0 <= step < 72:
        raise ValueError("Head fixture step outside reviewed range")
    return (30.0, 15.0) if 18 <= step < 36 else (-45.0, -20.0) if 36 <= step < 54 else (0.0, 0.0)


def head_inputs(step, backend):
    yaw, pitch = head_angles(step)
    if backend not in ("gecko", "bedrock"):
        raise ValueError("Unsupported head fixture backend")
    sign = -1 if backend == "gecko" else 1
    return {"limbSwing": 0.0, "limbSwingAmount": 0.0, "ageInTicks": float(step),
            "netHeadYaw": sign * yaw, "headPitch": sign * pitch, "partialTick": 0.0}


def validate_head_frame(frame, step):
    yaw, pitch = head_angles(step)
    expected = head_inputs(step, frame["backend"])
    inputs = frame["inputs"]
    if frame["setupAnimInvoked"] is not True or inputs["contract"] != HEAD_CONTRACT or inputs["render"] != expected:
        raise ValueError("Actual head render inputs differ at step " + str(step))
    if frame["netHeadYaw"] != yaw or frame["headPitch"] != pitch or frame["previousHeadPitch"] != pitch:
        raise ValueError("Actual entity head angles differ")
    if frame["bodyYaw"] != 180 or frame["previousBodyYaw"] != 180 or frame["headYaw"] != 180 + yaw or frame["previousHeadYaw"] != 180 + yaw:
        raise ValueError("Actual entity body/head frame differs")
    if frame["limbPosition"] != 0 or frame["limbSpeed"] != 0:
        raise ValueError("Head fixture unexpectedly walked")
    if frame["backend"] == "gecko":
        if frame["engineUpdated"] is not True:
            raise ValueError("Original Gecko update was skipped in the reviewed head sequence")
        query = inputs["query"]
        if query["bodyYaw"] != -180 or query["netHeadYaw"] != -yaw or query["headPitch"] != -pitch:
            raise ValueError("Original Gecko angular query convention differs")
        if query["groundSpeed"] != 0 or query["verticalSpeed"] != 0 or query["yawSpeed"] != 0:
            raise ValueError("Head fixture unexpectedly moved")
        if any(query[axis] != query["previous" + axis.upper()] for axis in "xyz"):
            raise ValueError("Head fixture has displacement")
        if frame["coreBones"].keys() != frame["bones"].keys() or frame["headBones"].keys() != frame["bones"].keys():
            raise ValueError("Missing actual Gecko stage bones")
        if (yaw != 0 or pitch != 0) and frame["coreBones"]["Head"] == frame["headBones"]["Head"]:
            raise ValueError("Nonzero original head input produced no head-stage effect")


def walk_inputs(step):
    if not 0 <= step < 72:
        raise ValueError("Walk fixture step outside reviewed range")
    walking = 24 <= step < 48
    return {"limbSwing": min(max(step - 24, 0), 24) * .25,
            "limbSwingAmount": .25 if walking else 0.0,
            "ageInTicks": float(step), "netHeadYaw": 0.0, "headPitch": 0.0, "partialTick": 0.0}, walking


def validate_walk_frame(frame, step):
    expected, walking = walk_inputs(step)
    inputs = frame["inputs"]
    if frame["setupAnimInvoked"] is not True:
        raise ValueError("Actual original animation input hook was not invoked")
    if inputs["contract"] != WALK_CONTRACT or inputs["render"] != expected:
        raise ValueError("Controlled walk render inputs differ at step " + str(step))
    if frame["limbPosition"] != expected["limbSwing"] or frame["limbSpeed"] != expected["limbSwingAmount"]:
        raise ValueError("Actual original walk state differs from render inputs")
    if frame["backend"] == "gecko":
        if frame["engineUpdated"] is not True:
            raise ValueError("Original Gecko update was skipped in the reviewed walk sequence")
        query = inputs["query"]
        if query["groundSpeed"] != (1.25 if walking else 0.0) or query["verticalSpeed"] != 0:
            raise ValueError("Original walk speed query differs")
        if query["y"] != query["previousY"] or query["z"] != query["previousZ"]:
            raise ValueError("Unexpected fixture displacement")
        if query["x"] - query["previousX"] != (.0625 if walking else 0.0):
            raise ValueError("Original synthetic displacement differs")
