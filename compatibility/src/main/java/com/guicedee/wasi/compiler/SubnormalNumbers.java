package com.guicedee.wasi.compiler;

import java.math.BigInteger;

/** Exact rounding of TeaVM's normalized decimal mantissa near binary64 underflow. */
public final class SubnormalNumbers {
    private SubnormalNumbers() { }
    public static double synthesize(long mantissa, int exponent, boolean negative) {
        if (exponent > -308) return original(mantissa, exponent, negative);
        long sign = negative ? Long.MIN_VALUE : 0;
        if (mantissa == 0 || exponent < -324) return Double.longBitsToDouble(sign);
        BigInteger unsigned = BigInteger.valueOf(mantissa);
        if (mantissa < 0) unsigned = unsigned.add(BigInteger.ONE.shiftLeft(64));
        BigInteger numerator = unsigned.shiftLeft(1074);
        BigInteger denominator = BigInteger.TEN.pow(18 - exponent);
        int binaryExponent = numerator.bitLength() - denominator.bitLength();
        if (binaryExponent >= 0 && numerator.compareTo(denominator.shiftLeft(binaryExponent)) < 0) binaryExponent--;
        int shift = Math.max(0, binaryExponent - 52);
        denominator = denominator.shiftLeft(shift);
        BigInteger[] divided = numerator.divideAndRemainder(denominator);
        long rounded = divided[0].longValue();
        int halfway = divided[1].shiftLeft(1).compareTo(denominator);
        if (halfway > 0 || (halfway == 0 && (rounded & 1) != 0)) rounded++;
        if (rounded < (1L << 52)) return Double.longBitsToDouble(sign | rounded);
        if (rounded == (1L << 53)) { rounded >>>= 1; shift++; }
        return Double.longBitsToDouble(sign | ((long) (shift + 1) << 52) | (rounded & ((1L << 52) - 1)));
    }
    // Rebound to the preserved TeaVM implementation by SubnormalNumberMode.
    private static double original(long mantissa, int exponent, boolean negative) {
        throw new UnsupportedOperationException("Compiler-only numeric delegation");
    }
}
