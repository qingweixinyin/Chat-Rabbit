package com.chatrabbit.alarm.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * 按“手机当前所在网段”生成自动检测候选地址。
 *
 * 以前是写死的常见网段列表，且 192.168.6.1 排第一：
 * 只要这个地址碰巧可达（比如家里另一台路由器/旧配置），
 * 不管手机实际在 192.168.3.x 还是 192.168.31.x，都会被设成 192.168.6.1。
 *
 * 现在：先看手机现在连的 Wi-Fi —— 默认网关、本机 IP 所在 /24 的 .1，
 * 用它们作为第一梯队；写死的常见网段只作兜底。
 */
object WifiRouteCandidates {

    /** 返回去重后的候选 baseUrl 列表（按优先级排序）。 */
    fun build(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        if (onWifi && network != null) {
            val lp = cm?.getLinkProperties(network)
            val gateway = lp?.routes
                ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress
            val phoneIp = lp?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address }
                ?.address?.hostAddress

            if (gateway != null) {
                out.add("http://$gateway:$DEFAULT_ROUTER_PORT")
                out.add("http://$gateway:$LEGACY_ROUTER_PORT")
            }
            if (phoneIp != null) {
                val prefix = phoneIp.substringBeforeLast('.', "")
                if (prefix.isNotEmpty()) {
                    out.add("http://$prefix.1:$DEFAULT_ROUTER_PORT")
                    out.add("http://$prefix.1:$LEGACY_ROUTER_PORT")
                }
            }
        }

        // 兜底：没连 Wi-Fi（或取不到网关）时，再按常见网段试
        out.addAll(RouterCandidates.DEFAULT_LIST)
        return out.toList()
    }
}
