package com.gu.mediaservice.lib.imgproxy

import com.gu.mediaservice.lib.logging.GridLogging

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.util.Try

/** Hex-encoded `IMGPROXY_KEY` / `IMGPROXY_SALT` used to sign imgproxy requests with an HMAC - see
 * `ImgProxyUrlBuilder.fixedUri` and `bbc/src/imgProxy/imgproxy-ecs-fargate.yaml` (which provisions these via
 * Secrets Manager for the deployed imgproxy service). Both must be present to sign a URL. */
case class ImgProxySigning(key: String, salt: String)

/**
 * Helper for building request URLs for [[https://imgproxy.net imgproxy]], used as a drop-in replacement for the
 * nginx-based `imgops` image resizing service.
 *
 * IMPORTANT - signing:
 * `fixedUri` will sign requests with an HMAC (imgproxy's standard signing scheme) when given an
 * `ImgProxySigning`, as required by any imgproxy deployment that doesn't set `IMGPROXY_ALLOW_INSECURE=true`
 * (e.g. the production-style ECS Fargate deployment - see `bbc/src/imgProxy/imgproxy-ecs-fargate.yaml` - where
 * "unsigned requests will fail"). Otherwise (e.g. local dev - see `dev/docker-compose.yml`, which sets
 * `IMGPROXY_ALLOW_INSECURE=true`) it falls back to imgproxy's "insecure" URL mode.
 *
 * `templatedUri` always uses "insecure" mode - see its doc comment for why it can't be signed.
 */
object ImgProxyUrlBuilder extends GridLogging {
  private val InsecureSignature = "insecure"

  /**
   * When running against LocalStack, the presigned S3 URL host (e.g. the `dev-nginx` mapped
   * `localstack.media.<domain>` vanity domain) isn't reachable from inside the imgproxy container. Since imgproxy
   * lives on the same docker-compose network as the `localstack` container, we rewrite the source URL to talk to
   * it directly - mirroring the trick already used in `dev/imgops/nginx.conf`.
   *
   * NOTE: we build the replacement URI from a raw string (and parse it with the single-arg `URI(String)`
   * constructor) rather than using the multi-arg `URI(scheme, userInfo, host, port, path, query, fragment)`
   * constructor. The multi-arg constructor treats its arguments as *decoded* components and will percent-encode
   * them itself - but `getRawPath`/`getRawQuery` are already percent-encoded (e.g. the S3 presigned URL's
   * `X-Amz-Signature` query params), so using it here would double-encode them and break the signature.
   */
  def normaliseSourceForLocalDev(uri: URI, awsLocalEndpoint: Option[String]): URI =
    awsLocalEndpoint match {
      case Some(_) =>
        val query = Option(uri.getRawQuery).map("?" + _).getOrElse("")
        new URI(s"http://localstack:4566${uri.getRawPath}$query")
      case None => uri
    }

  private def encodeSourceUrl(sourceUri: URI): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(sourceUri.toString.getBytes(StandardCharsets.UTF_8))

  private def normaliseRotation(rotationDegrees: Int): Int =
    if (rotationDegrees < 0) rotationDegrees + 360 else rotationDegrees

  private def hexToBytes(hex: String): Array[Byte] =
    hex.trim.stripPrefix("0x").replace(":", "").replace(" ", "")
      .grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  /**
   * imgproxy's standard HMAC-SHA256 signing scheme: `base64url(hmac_sha256(key, salt ++ path))`, where `path`
   * is the exact request path that follows the signature segment (i.e. `/<processing options>/<encoded url>`).
   * Both `key` and `salt` are hex-encoded strings, per imgproxy's own convention.
   */
  private def sign(signing: ImgProxySigning, path: String): Option[String] =
    Try {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(new SecretKeySpec(hexToBytes(signing.key), "HmacSHA256"))
      mac.update(hexToBytes(signing.salt))
      val digest = mac.doFinal(path.getBytes(StandardCharsets.UTF_8))
      Base64.getUrlEncoder.withoutPadding.encodeToString(digest)
    }.toOption

  private def signatureSegment(signing: Option[ImgProxySigning], path: String): String =
    signing.flatMap(sign(_, path)) match {
      case Some(signature) => signature
      case None =>
        if (signing.isDefined) {
          logger.error("Failed to sign imgproxy URL (bad IMGPROXY_KEY/IMGPROXY_SALT?) - falling back to " +
            "an insecure URL, which will be rejected unless IMGPROXY_ALLOW_INSECURE=true")
        }
        InsecureSignature
    }

  /**
   * Build a URI Template (RFC 6570) with `{w}`, `{h}` and `{q}` placeholders, so that callers (e.g. Kahuna, via
   * hyperagent's `image.follow(link, options)`) can expand it with concrete values at the point of use, exactly
   * as they already do for the equivalent imgops link.
   *
   * NOTE: the rotation option is `rot` (rotate by a fixed angle), NOT `rt` - `rt` is short for `resizing_type`
   * (e.g. `fit`/`fill`) and passing a numeric angle as its value causes imgproxy to reject the whole URL as
   * invalid (HTTP 404 "Invalid URL").
   *
   * NOTE - signing: this deliberately always uses imgproxy's "insecure" signature, and can't be changed to
   * sign requests: `{w}`/`{h}`/`{q}` are expanded into concrete values *client-side*, after this URL has left
   * the server, so any signature computed here would no longer match the final (expanded) request path.
   * Callers that need this to work against an imgproxy deployment that requires signed requests should instead
   * point at a same-origin redirect endpoint that calls `fixedUri` (with a real `ImgProxySigning`) per request.
   */
  def templatedUri(baseUri: String, sourceUri: URI, rotationDegrees: Int, awsLocalEndpoint: Option[String] = None): String = {
    val encoded = encodeSourceUrl(normaliseSourceForLocalDev(sourceUri, awsLocalEndpoint))
    val rotation = normaliseRotation(rotationDegrees)
    s"$baseUri/$InsecureSignature/w:{w}/h:{h}/q:{q}/rot:$rotation/$encoded"
  }

  /**
   * Build a fully resolved (non-templated) URL for a specific width/height/quality - e.g. for redirects or
   * server-side proxying. `width`/`height` of `0` keeps the original dimension unchanged.
   *
   * @param quality The `q:` (quality) processing option to request, or `None` to omit it entirely, in which
   *                case imgproxy falls back to its own configured default (`IMGPROXY_QUALITY`, typically
   *                80) rather than a caller-chosen value. This matters for "give me the original image back"
   *                use cases (see `MediaApi.downloadOriginalImage`): imgproxy always fully decodes/re-encodes
   *                the image (even with `w:0/h:0`), so pinning `q:100` there doesn't preserve the original
   *                bytes - it typically *inflates* file size by disabling/loosening chroma subsampling and
   *                progressive encoding relative to an already-compressed source. Leaving quality unset lets
   *                imgproxy apply a normal, size-conscious default instead. Metadata (EXIF/IPTC/XMP) is still
   *                stripped either way, per imgproxy's own default `IMGPROXY_STRIP_METADATA`/
   *                `IMGPROXY_KEEP_COPYRIGHT` behaviour (retaining only copyright/by-line tags).
   * @param signing When provided, the request is signed with imgproxy's standard HMAC scheme, as required by
   *                any imgproxy deployment that doesn't set `IMGPROXY_ALLOW_INSECURE=true`. When absent, falls
   *                back to imgproxy's "insecure" URL mode (fine for local dev only).
   */
  def fixedUri(
    baseUri: String,
    sourceUri: URI,
    width: Int,
    height: Int,
    quality: Option[Int],
    awsLocalEndpoint: Option[String] = None,
    rotationDegrees: Int = 0,
    signing: Option[ImgProxySigning] = None
  ): String = {
    val encoded = encodeSourceUrl(normaliseSourceForLocalDev(sourceUri, awsLocalEndpoint))
    val rotation = normaliseRotation(rotationDegrees)
    val qualitySegment = quality.map(q => s"/q:$q").getOrElse("")
    val path = s"/w:$width/h:$height$qualitySegment/rot:$rotation/$encoded"
    s"$baseUri/${signatureSegment(signing, path)}$path"
  }
}

