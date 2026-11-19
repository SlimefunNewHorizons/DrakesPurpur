//! Pillar A: Zero-copy Netty chunk packet compression & decompression (zlib-rs).
use flate2::read::{GzDecoder, ZlibDecoder};
use flate2::write::ZlibEncoder;
use flate2::Compression;
use std::io::{Read, Write};

use crate::{ERR_ARG, ERR_FORMAT, ERR_RANGE};

pub fn zlib_compress(input: &[u8], out: &mut [u8], level: u32) -> i32 {
    if level > 9 {
        return ERR_ARG;
    }
    let mut enc = ZlibEncoder::new(Vec::with_capacity(input.len() / 2 + 64), Compression::new(level));
    if enc.write_all(input).is_err() {
        return ERR_FORMAT;
    }
    let Ok(compressed) = enc.finish() else {
        return ERR_FORMAT;
    };
    if compressed.len() > out.len() {
        return ERR_RANGE;
    }
    out[..compressed.len()].copy_from_slice(&compressed);
    compressed.len() as i32
}

pub fn zlib_decompress(input: &[u8], out: &mut [u8]) -> i32 {
    let mut dec = ZlibDecoder::new(input);
    let mut total = 0usize;
    loop {
        if total == out.len() {
            let mut probe = [0u8; 1];
            match dec.read(&mut probe) {
                Ok(0) => return total as i32,
                Ok(_) => return ERR_RANGE,
                Err(_) => return ERR_FORMAT,
            }
        }
        match dec.read(&mut out[total..]) {
            Ok(0) => return total as i32,
            Ok(n) => total += n,
            Err(_) => return ERR_FORMAT,
        }
    }
}

pub fn gzip_decompress(input: &[u8], out: &mut [u8]) -> i32 {
    let mut dec = GzDecoder::new(input);
    let mut total = 0usize;
    loop {
        if total == out.len() {
            let mut probe = [0u8; 1];
            match dec.read(&mut probe) {
                Ok(0) => return total as i32,
                Ok(_) => return ERR_RANGE,
                Err(_) => return ERR_FORMAT,
            }
        }
        match dec.read(&mut out[total..]) {
            Ok(0) => return total as i32,
            Ok(n) => total += n,
            Err(_) => return ERR_FORMAT,
        }
    }
}
