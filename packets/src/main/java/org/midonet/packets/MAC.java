/*
 * Copyright (c) 2011 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.packets;

import java.util.Random;

import org.midonet.util.collection.WeakObjectPool;

import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonValue;

/** Reprensentation of a mac address. Utility class for a Ethernet-type Media
 *  Access Controll address ("Hardware" or "data link" or "link layer" address).
 *
 *  Conversion functions taking or returning long values assume the following
 *  encoding rules:
 *      1) the highest two bytes of the long value are ignored or set to 0;
 *      2) the ordering of bytes in the long from MSB to LSB follows the mac
 *          address representation as a string reading from left to right.
 */
public class MAC {
    private static WeakObjectPool<MAC> INSTANCE_POOL = new WeakObjectPool<MAC>();

    private static final Random rand = new Random();

    public static final long MAC_MASK = 0x0000_FFFF_FFFF_FFFFL;
    public static final long UNICAST_MASK = 0x1L << 40;

    private final long addr;

    public MAC(long address) {
      addr = MAC_MASK & address;
    }

    public MAC(byte[] rhs) {
        addr = bytesToLong(rhs);
    }

    public byte[] getAddress() {
        return longToBytes(addr);
    }

    @JsonCreator
    public static MAC fromString(String str) {
        return new MAC(MAC.stringToLong(str)).intern();
    }

    public static MAC fromAddress(byte[] rhs) {
        return new MAC(rhs).intern();
    }

    public MAC intern() {
        return INSTANCE_POOL.sharedRef(this);
    }

    public static MAC random() {
        byte[] addr = new byte[6];
        rand.nextBytes(addr);
        addr[0] &= ~0x01;
        return new MAC(addr);
    }

    public boolean unicast() {
        return 0 == (addr & UNICAST_MASK);
    }

    @JsonValue
    @Override
    public String toString() {
        return MAC.longToString(addr);
    }

    @Override
    public boolean equals(Object rhs) {
        if (this == rhs)
            return true;
        if (!(rhs instanceof MAC))
            return false;
        return this.addr == ((MAC)rhs).addr;
    }

    @Override
    public int hashCode() {
        return (int) (addr ^ (addr >>> 32));
    }

    private static IllegalArgumentException illegalMacString(String str) {
        return new IllegalArgumentException(
            "Mac address string must be 6 words of 1 or 2 hex digits " +
                "joined with 5 ':' but was " + str);
    }

    public static long stringToLong(String str)
            throws IllegalArgumentException {
        if (str == null)
            throw illegalMacString(str);
        String[] macBytes = str.split(":");
        if (macBytes.length != 6)
            throw illegalMacString(str);
        long addr = 0;
        try {
            for (String s : macBytes) {
                if (s.length() > 2)
                    throw illegalMacString(str);
                addr = (addr << 8) + (0xFFL & Integer.parseInt(s, 16));
            }
        } catch(NumberFormatException ex) {
            throw illegalMacString(str);
        }
        return addr;
    }

    public static byte[] stringToBytes(String str)
            throws IllegalArgumentException {
        if (str == null)
            throw illegalMacString(str);
        String[] macBytes = str.split(":");
        if (macBytes.length != 6)
            throw illegalMacString(str);

        byte[] addr = new byte[6];
        try {
            for (int i = 0; i < 6; i++) {
                String s = macBytes[i];
                if (s.length() > 2)
                    throw illegalMacString(str);
                addr[i] = (byte) Integer.parseInt(s, 16);
            }
        } catch(NumberFormatException ex) {
            throw illegalMacString(str);
        }
        return addr;
    }

    public static String longToString(long addr) {
        return String.format(
            "%02x:%02x:%02x:%02x:%02x:%02x",
            (addr & 0xff0000000000L) >> 40,
            (addr & 0x00ff00000000L) >> 32,
            (addr & 0x0000ff000000L) >> 24,
            (addr & 0x000000ff0000L) >> 16,
            (addr & 0x00000000ff00L) >> 8,
            (addr & 0x0000000000ffL)
        );
    }

    public static byte[] longToBytes(long addr) {
        byte[] bytesAddr = new byte[6];
        for (int i = 5; i >= 0; i--) {
            bytesAddr[i] = (byte)(addr & 0xffL);
            addr = addr >> 8;
        }
        return bytesAddr;
    }

    private static IllegalArgumentException illegalMacBytes =
        new IllegalArgumentException(
            "byte array representing a MAC address must have length 6 exactly");

    public static long bytesToLong(byte[] bytesAddr)
            throws IllegalArgumentException {
        if (bytesAddr == null || bytesAddr.length != 6)
             throw illegalMacBytes;
        long addr = 0;
        for (int i = 0; i < 6; i++) {
            addr = (addr << 8) + (bytesAddr[i] & 0xffL);
        }
        return addr;
    }

    public static String bytesToString(byte[] address)
            throws IllegalArgumentException {
        if (address == null || address.length != 6)
            throw illegalMacBytes;
        return String.format(
            "%02x:%02x:%02x:%02x:%02x:%02x",
            address[0],
            address[1],
            address[2],
            address[3],
            address[4],
            address[5]
        );
    }
}
