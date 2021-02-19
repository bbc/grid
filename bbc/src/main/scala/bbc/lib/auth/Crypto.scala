package bbc.lib.auth

import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.security.{KeyFactory, Security, Signature}
import org.apache.commons.codec.binary.Base64._
import org.bouncycastle.jce.provider.BouncyCastleProvider


object Crypto {
  case class PublicKey(key: String) extends AnyVal
  case class PrivateKey(key: String) extends AnyVal
  case class Secret(secret: String) extends AnyVal


  /**
    * You can generate a das key pair as follows:
    *
    * openssl genrsa -out private_key.pem 4096
    * openssl rsa -pubout -in private_key.pem -out public_key.pem
    *
    * Note: you only need to pass the key ie the blob of base64 between the start and end markers in the pem file.
    */

  Security.addProvider(new BouncyCastleProvider())

  val signatureAlgorithm: String = "SHA256withRSA"
  val keyFactory = KeyFactory.getInstance("RSA")

  def signData(data: Array[Byte], prvKey: PrivateKey): Array[Byte] = {
    val rsa = Signature.getInstance(signatureAlgorithm, "BC")
    val privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decodeBase64(prvKey.key)))
    rsa.initSign(privateKey)

    rsa.update(data)
    rsa.sign()
  }

  def verifySignature(data: Array[Byte], signature: Array[Byte], pubKey: PublicKey) : Boolean = {
    val rsa = Signature.getInstance(signatureAlgorithm, "BC")
    val publicKey = keyFactory.generatePublic(new X509EncodedKeySpec(decodeBase64(pubKey.key)))
    rsa.initVerify(publicKey)

    rsa.update(data)
    rsa.verify(signature)
  }
}
