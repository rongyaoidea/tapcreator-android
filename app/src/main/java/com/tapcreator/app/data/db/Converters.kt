package com.tapcreator.app.data.db

import androidx.room.TypeConverter
import com.tapcreator.app.data.model.ConversationSurface
import com.tapcreator.app.data.model.MediaKind
import com.tapcreator.app.data.model.Protocol
import com.tapcreator.app.data.model.RunStatus
import com.tapcreator.app.data.model.TaskStatus

class TapcreatorConverters {
    @TypeConverter
    fun mediaKindToString(kind: MediaKind): String = kind.name

    @TypeConverter
    fun stringToMediaKind(value: String): MediaKind = MediaKind.valueOf(value)

    @TypeConverter
    fun surfaceToString(surface: ConversationSurface): String = surface.name

    @TypeConverter
    fun stringToSurface(value: String): ConversationSurface = ConversationSurface.valueOf(value)

    @TypeConverter
    fun runStatusToString(status: RunStatus): String = status.name

    @TypeConverter
    fun stringToRunStatus(value: String): RunStatus = RunStatus.valueOf(value)

    @TypeConverter
    fun taskStatusToString(status: TaskStatus): String = status.name

    @TypeConverter
    fun stringToTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)

    @TypeConverter
    fun protocolToString(protocol: Protocol): String = protocol.name

    @TypeConverter
    fun stringToProtocol(value: String): Protocol = Protocol.valueOf(value)
}