@file:UseSerializers(OffsetSerializer::class)

package com.wx.mapper.model

import androidx.compose.ui.geometry.Offset
import com.wx.mapper.data.serializers.OffsetSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers


@Serializable
sealed class DraggableItem {

    abstract val id: Int
    abstract var position: Offset
    abstract fun copy(id: Int=this.id,position: Offset=this.position) :DraggableItem
@Serializable
    data class VariableKey(
        override val id: Int,
        override var position: Offset,
        var keyCode:Int? = null,
        var size: Int,
        /** true = tecla ALTERNAR: um toque segura o ponto da tela, outro toque solta. */
        var toggle: Boolean = false
    ) : DraggableItem(){
        override fun copy(id: Int, position: Offset): DraggableItem =
            VariableKey(id, position, keyCode, size, toggle)

    }

    @Serializable
    data class WASDGroup(
        override val id: Int,
        override var position:Offset,
        var scale: Float=1f,
        var center: Offset = Offset.Zero,
        var w: Offset = Offset.Zero,
        var a: Offset = Offset.Zero,
        var s: Offset = Offset.Zero,
        var d: Offset = Offset.Zero,
        /** Largura medida (px) do grupo, sem escala; usada para calcular o joystick. */
        var size: Int = 0,
    ) : DraggableItem() {
        override fun copy(id: Int, position: Offset): DraggableItem =
            WASDGroup(id, position,scale, center, w, a, s, d, size)
    }

    @Serializable
    data class FixedKey(
        override val id: Int,
        override var position: Offset,
        @SerialName("itemType")
        val type: DraggableItemType,
        var keyCode: Int,
        var size: Int
    ) : DraggableItem(){
        override fun copy(id: Int, position: Offset): DraggableItem =
            FixedKey(id, position, type, keyCode, size)
    }
    @Serializable
    data class CancelableKey(
        override val id: Int,
        override var position: Offset,
        var cancelPosition :Offset,
        @SerialName("itemType")
        val type: DraggableItemType,
        var keyCode: Int?=null,
        var size: Int
    ) : DraggableItem(){
        override fun copy(id: Int, position: Offset): DraggableItem =
            CancelableKey(id, position,cancelPosition, type, keyCode, size)
    }
}

@Serializable
data class KeyMap(
    val type: KeymapType = KeymapType.DEFAULT,
    val position: Offset,
    val center: Offset? = null,
    val end: Offset? = null,
    /** Tecla ALTERNAR (segura até apertar de novo). */
    val toggle: Boolean = false,
)

enum class KeymapType {
    DEFAULT,CANCELABLE
}

@Serializable
enum class DraggableItemType {
    //do not change positions of existing items
    KEY, WASD_KEY, SHOOTING_MODE, FIRE, BAG_MAP , SCOPE, TOGGLE_KEY
}
