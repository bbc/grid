package com.gu.mediaservice.lib.http

import org.apache.http.config.{Registry, RegistryBuilder}
import org.apache.http.conn.socket.{ConnectionSocketFactory, PlainConnectionSocketFactory}
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager

object HttpClient {

  def configuredPooledConnectionManager: PoolingHttpClientConnectionManager = {
    val registry: Registry[ConnectionSocketFactory] =
      RegistryBuilder.create[ConnectionSocketFactory]
        .register("http", PlainConnectionSocketFactory.getSocketFactory)
        .register("https", SSLConnectionSocketFactory.getSystemSocketFactory)
        .build

    new PoolingHttpClientConnectionManager(registry)
  }
}
