package com.yahoo.ycsb.dual.tests;

import com.yahoo.ycsb.dual.LonghairLib;

import java.io.UnsupportedEncodingException;
import java.util.List;

/**
 * Created by ubuntu on 09.01.16.
 */
public class LonghairTest {
    public static void main(String[] args) throws InterruptedException {
        LonghairLib.k = 4;
        LonghairLib.m = 2;

        if (LonghairLib.Longhair.INSTANCE._cauchy_256_init(2) != 0) {
            System.err.println("Failed to init longhair lib!");
        }

        // test encode
        byte[] original = "Hello, kitty kitty kitty kitty kitty kitty kitty kitty kitty!".getBytes();
        try {
            System.out.println("Original: " + new String(original, "US-ASCII"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        List<byte[]> blocks = LonghairLib.encode(original);

        blocks.remove(2);
        blocks.remove(2);

        // test decode
        byte[] decoded = LonghairLib.decode(blocks);
        try {
            System.out.println("Decoded: " + new String(decoded, "US-ASCII"));
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }
}