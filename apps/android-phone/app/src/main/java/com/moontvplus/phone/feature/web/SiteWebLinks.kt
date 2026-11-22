package com.moontvplus.phone.feature.web

import com.moontvplus.phone.core.network.ServerAddress
import com.moontvplus.ui.model.WebPage

/** 把共享层的站内网页入口拼成真实 URL，保留站点子路径部署。 */
object SiteWebLinks {
    fun url(base: ServerAddress, page: WebPage): String {
        val path = page.path.trim('/')
        val url = if (path.isEmpty()) base.base else base.base.newBuilder().addPathSegments(path).build()
        return url.toString()
    }
}
