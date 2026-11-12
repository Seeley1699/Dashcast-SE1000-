package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

internal fun Parcel.writeBooleanCompat(value: Boolean) = writeInt(if (value) 1 else 0)
internal fun Parcel.readBooleanCompat(): Boolean = readInt() != 0

internal fun Parcel.writeNullableLongCompat(value: Long?) {
    writeBooleanCompat(value != null)
    if (value != null) writeLong(value)
}

internal fun Parcel.readNullableLongCompat(): Long? =
    if (readBooleanCompat()) readLong() else null

internal fun Parcel.writeNullableIntCompat(value: Int?) {
    writeBooleanCompat(value != null)
    if (value != null) writeInt(value)
}

internal fun Parcel.readNullableIntCompat(): Int? =
    if (readBooleanCompat()) readInt() else null

internal fun Parcel.writeNullableDoubleCompat(value: Double?) {
    writeBooleanCompat(value != null)
    if (value != null) writeDouble(value)
}

internal fun Parcel.readNullableDoubleCompat(): Double? =
    if (readBooleanCompat()) readDouble() else null

internal fun Parcel.writeNullableBooleanCompat(value: Boolean?) {
    writeBooleanCompat(value != null)
    if (value != null) writeBooleanCompat(value)
}

internal fun Parcel.readNullableBooleanCompat(): Boolean? =
    if (readBooleanCompat()) readBooleanCompat() else null

internal fun <T : Parcelable> Parcel.writeOptional(value: T?, flags: Int) {
    writeBooleanCompat(value != null)
    value?.writeToParcel(this, flags)
}

internal fun <T : Parcelable> Parcel.readOptional(creator: Parcelable.Creator<T>): T? =
    if (readBooleanCompat()) creator.createFromParcel(this) else null

internal fun Parcel.readRequiredString(): String = readString().orEmpty()
