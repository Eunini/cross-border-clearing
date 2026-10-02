//! A small, fast, non-cryptographic hasher for the engine's internal maps.
//!
//! Keys are participant codes, currency codes and caller-assigned ids from a
//! trusted internal caller, so HashDoS resistance (the reason std uses
//! SipHash) is not needed here, while hashing millions of short keys per
//! cycle is on the hot path. This is the multiply-rotate scheme popularised
//! by Firefox and rustc ("FxHash").

use std::collections::{HashMap, HashSet};
use std::hash::{BuildHasherDefault, Hasher};

const SEED: u64 = 0x51_7c_c1_b7_27_22_0a_95;

#[derive(Default, Clone, Copy)]
pub struct FxHasher {
    hash: u64,
}

impl FxHasher {
    #[inline]
    fn add(&mut self, word: u64) {
        self.hash = (self.hash.rotate_left(5) ^ word).wrapping_mul(SEED);
    }
}

impl Hasher for FxHasher {
    #[inline]
    fn write(&mut self, bytes: &[u8]) {
        let mut chunks = bytes.chunks_exact(8);
        for c in &mut chunks {
            self.add(u64::from_le_bytes(c.try_into().unwrap()));
        }
        let rest = chunks.remainder();
        if !rest.is_empty() {
            let mut buf = [0u8; 8];
            buf[..rest.len()].copy_from_slice(rest);
            self.add(u64::from_le_bytes(buf));
        }
    }

    #[inline]
    fn write_u8(&mut self, i: u8) {
        self.add(i as u64);
    }

    #[inline]
    fn write_u64(&mut self, i: u64) {
        self.add(i);
    }

    #[inline]
    fn write_usize(&mut self, i: usize) {
        self.add(i as u64);
    }

    #[inline]
    fn finish(&self) -> u64 {
        self.hash
    }
}

pub type FxBuildHasher = BuildHasherDefault<FxHasher>;
pub type FxHashMap<K, V> = HashMap<K, V, FxBuildHasher>;
pub type FxHashSet<K> = HashSet<K, FxBuildHasher>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distinguishes_short_keys() {
        let mut m: FxHashMap<&str, u32> = FxHashMap::default();
        for (i, k) in ["USD", "EUR", "GBP", "JPY", "XB01USNY", "XB02USNY"]
            .iter()
            .enumerate()
        {
            m.insert(k, i as u32);
        }
        assert_eq!(m.len(), 6);
        assert_eq!(m["XB02USNY"], 5);
    }
}
