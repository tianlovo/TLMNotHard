/*
 * Ported from TouhouLittleMaid 8a3ac5e9eacf0c6d63adf8832949f3ce81484fe6
 * Original: com/github/tartaricacid/simplebedrockmodel/client/bedrock/pojo/FaceUVsItem.java
 * MIT, see licenses/TouhouLittleMaid-MIT.txt
 * Adaptation: Forge 1.7.10 types and legacy matrix/vertex interfaces
 */
package com.github.tartaricacid.simplebedrockmodel.client.bedrock.pojo;

import javax.annotation.Nullable;

import com.google.gson.annotations.SerializedName;
import com.tianluoqaq.tlmnh.client.render.ModelDirection;

public class FaceUVsItem {

    @SerializedName("down")
    private FaceItem down;
    @SerializedName("east")
    private FaceItem east;
    @SerializedName("north")
    private FaceItem north;
    @SerializedName("south")
    private FaceItem south;
    @SerializedName("up")
    private FaceItem up;
    @SerializedName("west")
    private FaceItem west;

    public FaceUVsItem(FaceItem down, FaceItem east, FaceItem north, FaceItem south, FaceItem up, FaceItem west) {
        this.down = down;
        this.east = east;
        this.north = north;
        this.south = south;
        this.up = up;
        this.west = west;
    }

    @Nullable
    public FaceItem getFace(ModelDirection direction) {
        return switch (direction) {
            case EAST -> west;
            case WEST -> east;
            case NORTH -> north;
            case SOUTH -> south;
            case UP -> down;
            default -> up;
        };
    }
}
