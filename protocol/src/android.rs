use crate::{Interval, encode, heart_intervals, step_intervals, valid};
use jni::{
    JNIEnv,
    objects::{JByteArray, JObject},
    sys::{jbyteArray, jint, jintArray},
};
use std::ptr::null_mut;

#[unsafe(no_mangle)]
extern "system" fn Java_org_openhk50_band_NativeProtocol_encode(
    env: JNIEnv,
    _: JObject,
    command: jint,
    payload: JByteArray,
) -> jbyteArray {
    let result = env
        .convert_byte_array(payload)
        .ok()
        .and_then(|bytes| encode(command as u8, &bytes))
        .and_then(|frame| env.byte_array_from_slice(&frame).ok());
    result.map_or(null_mut(), |array| array.into_raw())
}

#[unsafe(no_mangle)]
extern "system" fn Java_org_openhk50_band_NativeProtocol_header(
    env: JNIEnv,
    _: JObject,
    bytes: JByteArray,
) -> jint {
    let mut frame = [0_i8; 16];
    if !matches!(env.get_array_length(&bytes), Ok(16))
        || env.get_byte_array_region(bytes, 0, &mut frame).is_err()
    {
        return 0;
    }
    let frame = frame.map(|value| value as u8);
    if !valid(&frame) {
        return 0;
    }
    jint::from_be_bytes([frame[0], frame[1], frame[2], frame[3]])
}

#[unsafe(no_mangle)]
extern "system" fn Java_org_openhk50_band_NativeProtocol_history(
    env: JNIEnv,
    _: JObject,
    frames: JByteArray,
    date: jint,
) -> jintArray {
    let Ok(bytes) = env.convert_byte_array(frames) else {
        return null_mut();
    };
    let intervals = if date == 0 {
        heart_intervals(&bytes)
    } else {
        step_intervals(&bytes, date as u32)
    };
    int_array(env, intervals).unwrap_or(null_mut())
}

fn int_array(env: JNIEnv, values: Vec<Interval>) -> jni::errors::Result<jintArray> {
    let packed: Vec<jint> = values
        .into_iter()
        .map(|reading| (jint::from(reading.minute) << 16) | jint::from(reading.value))
        .collect();
    let array = env.new_int_array(packed.len() as i32)?;
    env.set_int_array_region(&array, 0, &packed)?;
    Ok(array.into_raw())
}
