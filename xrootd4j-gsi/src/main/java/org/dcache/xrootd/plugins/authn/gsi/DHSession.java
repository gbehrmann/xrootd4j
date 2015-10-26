/**
 * Copyright (C) 2011-2015 dCache.org <support@dcache.org>
 *
 * This file is part of xrootd4j.
 *
 * xrootd4j is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * xrootd4j is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with xrootd4j.  If not, see http://www.gnu.org/licenses/.
 */
package org.dcache.xrootd.plugins.authn.gsi;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.DHParameter;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

/**
 * This class represents a Diffie-Hellman (DH) session. After the DH key agreement
 * has been completed, the resulting session key can be used for (symmetric) encryption/
 * decryption.
 *
 * @author radicke
 * @author tzangerl
 *
 */
public class DHSession
{
    private static final String DH_ALGORITHM_NAME = "DH";
    private static final String DH_HEADER = "-----BEGIN DH PARAMETERS-----";
    private static final String DH_FOOTER = "-----END DH PARAMETERS-----";
    private static final String DH_PUBKEY_HEADER = "---BPUB---";
    private static final String DH_PUBKEY_FOOTER = "---EPUB---";

    // The 512-bit prime being part of the DH parameter set.
    // This specific number set was created by using Openssl and passes
    // its validity tests and is therefore considered to be safe.
    private static final String DH_PRIME =
            ( "00:a8:37:9d:6f:ff:e8:63:a0:b1:47:0c:26:dd:1a:"
            + "45:0b:e2:03:9a:f0:83:b1:ba:5b:fa:1d:2f:5b:2a:"
            + "89:08:02:d8:c4:d4:66:8d:14:8d:35:bb:24:b1:af:"
            + "1a:d3:75:c7:c0:3b:61:aa:85:3f:56:69:ae:f2:67:"
            + "da:20:87:5d:93" ).replaceAll("[:\\s]+", "");

    // the 512 bit DH parameter set used for all DH sessions, consisting
    // of the prime above and the generator value of 2
    private static final DHParameterSpec DH_PARAMETERS = new DHParameterSpec(
            new BigInteger(DH_PRIME, 16), BigInteger.valueOf(2));

    private KeyPair _localDHKeyPair;
    private KeyAgreement _keyAgreement;

    /**
     * Construct new Diffie-Hellman key exchange session
     * @throws InvalidAlgorithmParameterException Invalid DH parameters (primes)
     * @throws NoSuchAlgorithmException DH algorithm not available in VM
     * @throws InvalidKeyException Private key generated by DH generator invalid
     * @throws NoSuchProviderException Bouncy castle provider does not exist
     */
    public DHSession()
        throws InvalidAlgorithmParameterException, NoSuchAlgorithmException,
            InvalidKeyException, NoSuchProviderException
    {
        KeyPairGenerator kpairGen =
            KeyPairGenerator.getInstance(DH_ALGORITHM_NAME, "BC");
        kpairGen.initialize(DH_PARAMETERS);
        _localDHKeyPair = kpairGen.generateKeyPair();

        _keyAgreement = KeyAgreement.getInstance(DH_ALGORITHM_NAME, "BC");
        _keyAgreement.init(_localDHKeyPair.getPrivate());
    }

    public String getEncodedDHMaterial() throws IOException
    {
        String dhparams =
            CertUtil.toPEM(toDER(DH_PARAMETERS), DH_HEADER, DH_FOOTER);
        DHPublicKey pubkey = (DHPublicKey) _localDHKeyPair.getPublic();

        return dhparams + '\n' + DH_PUBKEY_HEADER + pubkey.getY().toString(16) + DH_PUBKEY_FOOTER;
    }

    public void finaliseKeyAgreement(String dhmessage) throws IOException,
            GeneralSecurityException, NoSuchAlgorithmException,
            InvalidKeySpecException, InvalidKeyException, IllegalStateException
    {
        int delimitingIndex = dhmessage.indexOf(DH_PUBKEY_HEADER);

        if (delimitingIndex < 0 || delimitingIndex >= dhmessage.length()) {
            throw new IllegalArgumentException("Illegal DH message: "
                    + dhmessage);
        }

        String dhparams = dhmessage.substring(0, delimitingIndex);
        String remotePubKeyString = dhmessage.substring(delimitingIndex);

        DHParameterSpec params = fromDER(CertUtil.fromPEM(dhparams,
                                                          DH_HEADER,
                                                          DH_FOOTER));

        if (!(DH_PARAMETERS.getP().equals(params.getP()) && DH_PARAMETERS
                .getG().equals(params.getG()))) {
            throw new GeneralSecurityException(
                    "remote DH parameters differ from local ones");
        }

        removeCharFromString(remotePubKeyString, '\n');

        int envLength = DH_PUBKEY_HEADER.length();
        remotePubKeyString = remotePubKeyString.substring(envLength,
                remotePubKeyString.length() - envLength);

        // parse hex String into a BigInt
        BigInteger remoteY = new BigInteger(remotePubKeyString, 16);

        // convert into a public key
        KeyFactory keyfac = KeyFactory.getInstance(DH_ALGORITHM_NAME, "BC");
        PublicKey remotePubKey = keyfac.generatePublic(new DHPublicKeySpec(
                remoteY, params.getP(), params.getG()));

        // finalise DH key agreement
        _keyAgreement.doPhase(remotePubKey, true);
    }

    /**
     * remove all occurences of a character
     *
     * @param sb
     *            the stringbuilder
     * @param c
     *            the char to be removed
     * @return the resulting stringbuilder
     */
    private StringBuilder removeChar(StringBuilder sb, char c)
    {
        int index;
        while ((index = sb.indexOf("\n")) > -1) {
            sb.deleteCharAt(index);
        }
        return sb;
    }

    public byte[] decrypt(String cipherSpec,
                          String keySpec,
                          int blocksize,
                          byte[] encrypted)
        throws InvalidKeyException,
               IllegalStateException, NoSuchAlgorithmException,
               NoSuchPaddingException, IllegalBlockSizeException,
               BadPaddingException, InvalidAlgorithmParameterException,
               NoSuchProviderException
    {
        byte [] iv = new byte[blocksize];
        Arrays.fill(iv, (byte)0);
        final byte[] sharedSecret = _keyAgreement.generateSecret();
        /* need a 128-bit key, that's the way to get it */
        SecretKey sessionKey = new SecretKeySpec(sharedSecret,
                                                 0,
                                                 blocksize,
                                                 keySpec);
        IvParameterSpec paramSpec = new IvParameterSpec(iv);

        Cipher cipher = Cipher.getInstance(cipherSpec,
                                           "BC");
        cipher.init(Cipher.DECRYPT_MODE, sessionKey, paramSpec);

        return cipher.doFinal(encrypted);
    }

    /**
     * remove all occurences of a character
     *
     * @param s
     *            the string
     * @param c
     *            the char to be removed
     * @return the resulting string
     */
    private String removeCharFromString(String s, char c)
    {
        return s.replaceAll(String.valueOf(c), "");
    }

    /**
     * Creates an DHParameterSpec object from the DER-encoded byte sequence
     * @param der the DER-encoded byte sequence
     * @return the DHParameterSpec object
     * @throws IOException if the deserialisation goes wrong
     */
    private DHParameterSpec fromDER(byte[] der) throws IOException
    {
        ByteArrayInputStream inStream = new ByteArrayInputStream(der);
        ASN1InputStream derInputStream = new ASN1InputStream(inStream);
        DHParameter dhparam = DHParameter.getInstance(derInputStream.readObject());
        return new DHParameterSpec(dhparam.getP(), dhparam.getG());
    }

    /**
     * Creates an DER-encoded byte sequence from the DHParameter object
     * @param paramspec the DH parameter object
     * @return the DER-encoded byte sequence of the DH Parameter object
     */
    private byte[] toDER(DHParameterSpec paramspec) throws IOException
    {
        DHParameter derParams = new DHParameter(paramspec.getP(), // Prime
                                                                  // (public
                                                                  // key)
                paramspec.getG(), // generator
                paramspec.getP().bitLength()); // keylength of Prime

        return derParams.getEncoded("DER");
    }
}
