/*

    Copyright 2018-2022 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.core.util;

import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Arrays;

import org.junit.Assert;

public class CryptoTest {
    private static final Logger log = LoggerFactory.getLogger(CryptoTest.class);

    private static final CryptoApi crypto = new CryptoApi();
    private static boolean strongCrypto;

    @BeforeClass
    public static void checkCrypto() {
        strongCrypto = crypto.strongCryptoSupported();
        if (!strongCrypto) {
            log.warn("Not using Java Cryptography Extension (JCE) Unlimited Strength Jurisdiction Policy");
            log.info("AES-128 supported");
        } else {
            log.info("Using Java Cryptography Extension (JCE) Unlimited Strength Jurisdiction Policy");
            log.info("AES-256 supported");
        }
    }

    @Test
    public void aesEncryptionTest() throws IOException, GeneralSecurityException {
        String input = "hello world";
        byte[] key = crypto.generateAesKey(strongCrypto? 256 : 128);
        byte[] encrypted = crypto.aesEncrypt(input.getBytes(), key);
        byte[] decrypted = crypto.aesDecrypt(encrypted, key);
        Assert.assertEquals(input, new String(decrypted));
    }

    @Test
    public void rsaEncryptionTest() throws GeneralSecurityException {
        // RSA encryption is usually used to transport symmetric encryption key
        byte[] input = crypto.generateAesKey(256);
        KeyPair kp = crypto.generateRsaKey();
        byte[] pub = kp.getPublic().getEncoded();
        byte[] pri = kp.getPrivate().getEncoded();
        // encrypt
        byte[] encrypted = crypto.rsaEncrypt(input, pub);
        // decrypt
        byte[] decrypted = crypto.rsaDecrypt(encrypted, pri);
        // cannot use Assert.assertEquals because we are comparing byte-by-byte
        Assert.assertTrue(Arrays.equals(input, decrypted));
    }

    @Test
    public void dsaSignatureTest() throws GeneralSecurityException {
        KeyPair kp = crypto.generateDsaKey();
        byte[] pub = kp.getPublic().getEncoded();
        byte[] pri = kp.getPrivate().getEncoded();
        byte[] data = "hello world".getBytes();
        byte[] signature = crypto.dsaSign(data, pri);
        boolean result = crypto.dsaVerify(data, signature, pub);
        Assert.assertTrue(result);
    }

    @Test
    public void rsaSignatureTest() throws GeneralSecurityException {
        KeyPair kp = crypto.generateRsaKey();
        byte[] pub = kp.getPublic().getEncoded();
        byte[] pri = kp.getPrivate().getEncoded();
        byte[] data = "hello world".getBytes();
        byte[] signature = crypto.rsaSign(data, pri);
        boolean result = crypto.rsaVerify(data, signature, pub);
        Assert.assertTrue(result);
    }

    @Test
    public void hashTest() {
        String input = "hello world";
        byte[] hashed = crypto.getSHA256(input.getBytes());
        Assert.assertEquals(32, hashed.length);
        hashed = crypto.getSHA1(input.getBytes());
        Assert.assertEquals(20, hashed.length);
        hashed = crypto.getMd5(input.getBytes());
        Assert.assertEquals(16, hashed.length);
    }

    @Test
    public void hmac1Test() {
        String expected = "8a3a84bcd0d0065e97f175d370447c7d02e00973";
        byte[] key = "hello".getBytes();
        byte[] message = "world".getBytes();
        byte[] b = crypto.getHmacSha1(key, message);
        Assert.assertEquals(expected, Utility.getInstance().bytes2hex(b));
    }

    @Test
    public void hmac256Test() {
        String expected = "f1ac9702eb5faf23ca291a4dc46deddeee2a78ccdaf0a412bed7714cfffb1cc4";
        byte[] key = "hello".getBytes();
        byte[] message = "world".getBytes();
        byte[] b = crypto.getHmacSha256(key, message);
        Assert.assertEquals(expected, Utility.getInstance().bytes2hex(b));
    }

}
