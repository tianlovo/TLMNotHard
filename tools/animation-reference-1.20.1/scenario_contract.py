"""两个有界动画夹具的输入合同，不作为通用游戏轨迹协议"""

IDLE_MODE = "controlled-idle-v0"
WALK_MODE = "controlled-idle-walk-idle-v1"
IDLE_CONTRACT = "controlled-empty-idle-v1"
WALK_CONTRACT = "controlled-empty-idle-walk-idle-v1"


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
