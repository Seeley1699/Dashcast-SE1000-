package com.kooo.evcc.evccapi

import android.os.Parcel
import android.os.Parcelable

data class EvccValue @JvmOverloads constructor(
    val type: String = EvccApiContract.ValueType.NULL,
    val stringValue: String? = null,
    val longValue: Long? = null,
    val doubleValue: Double? = null,
    val booleanValue: Boolean? = null,
    val rawValue: String? = null,
    val displayValue: String = "",
    val initialized: Boolean = false,
    val status: String = EvccApiContract.Status.PENDING,
    val observedAt: Long = 0,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        parcel.readRequiredString(), parcel.readString(), parcel.readNullableLongCompat(),
        parcel.readNullableDoubleCompat(), parcel.readNullableBooleanCompat(), parcel.readString(),
        parcel.readRequiredString(), parcel.readBooleanCompat(), parcel.readRequiredString(), parcel.readLong(),
    )
    override fun writeToParcel(dest: Parcel, flags: Int) = with(dest) {
        writeString(type); writeString(stringValue); writeNullableLongCompat(longValue)
        writeNullableDoubleCompat(doubleValue); writeNullableBooleanCompat(booleanValue); writeString(rawValue)
        writeString(displayValue); writeBooleanCompat(initialized); writeString(status); writeLong(observedAt)
    }
    override fun describeContents() = 0
    companion object CREATOR : Parcelable.Creator<EvccValue> {
        override fun createFromParcel(parcel: Parcel) = EvccValue(parcel)
        override fun newArray(size: Int) = arrayOfNulls<EvccValue>(size)

        @JvmStatic fun ofString(value: String, observedAt: Long = System.currentTimeMillis()) =
            EvccValue(EvccApiContract.ValueType.STRING, stringValue = value, rawValue = value,
                displayValue = value, initialized = true, status = EvccApiContract.Status.AVAILABLE,
                observedAt = observedAt)
        @JvmStatic fun ofLong(value: Long, observedAt: Long = System.currentTimeMillis()) =
            EvccValue(EvccApiContract.ValueType.LONG, longValue = value, rawValue = value.toString(),
                displayValue = value.toString(), initialized = true, status = EvccApiContract.Status.AVAILABLE,
                observedAt = observedAt)
        @JvmStatic fun ofDouble(value: Double, observedAt: Long = System.currentTimeMillis()) =
            EvccValue(EvccApiContract.ValueType.DOUBLE, doubleValue = value, rawValue = value.toString(),
                displayValue = value.toString(), initialized = true, status = EvccApiContract.Status.AVAILABLE,
                observedAt = observedAt)
        @JvmStatic fun ofBoolean(value: Boolean, observedAt: Long = System.currentTimeMillis()) =
            EvccValue(EvccApiContract.ValueType.BOOLEAN, booleanValue = value, rawValue = value.toString(),
                displayValue = value.toString(), initialized = true, status = EvccApiContract.Status.AVAILABLE,
                observedAt = observedAt)
    }
}
