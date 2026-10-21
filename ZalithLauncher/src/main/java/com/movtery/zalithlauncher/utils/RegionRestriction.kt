/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.utils

import com.movtery.zalithlauncher.path.GLOBAL_CLIENT
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.network.safeBodyAsJson
import io.ktor.client.request.get
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "RegionRestriction"

/** 单个 IP 归属查询服务的超时时间 */
private const val PROVIDER_TIMEOUT_MILLIS = 5_000L

/** IP 归属查询服务，依次尝试，以第一个成功返回的结果为准 */
private val GEO_IP_PROVIDERS = listOf(
    "https://ipwho.is/",
    "https://ipapi.co/json/",
    "https://ipinfo.io/json"
)

/**
 * 中国大陆地区功能限制检测结果
 */
sealed interface RegionRestrictionState {
    /** 此状态下，受地区限制的功能是否应当被禁用 */
    val restricting: Boolean

    /** 尚未开始检测 */
    data object Unknown : RegionRestrictionState {
        override val restricting: Boolean = false
    }

    /** 机器特征命中中国大陆，正在通过出口 IP 归属确认 */
    data object Checking : RegionRestrictionState {
        override val restricting: Boolean = true
    }

    /** 确认位于中国大陆，受地区限制的功能应当被禁用 */
    data object Restricted : RegionRestrictionState {
        override val restricting: Boolean = true
    }

    /** 非中国大陆地区 */
    data object Allowed : RegionRestrictionState {
        override val restricting: Boolean = false
    }
}

object RegionRestriction {
    private val _state = MutableStateFlow<RegionRestrictionState>(RegionRestrictionState.Unknown)
    val state: StateFlow<RegionRestrictionState> = _state.asStateFlow()

    private val checkMutex = Mutex()

    suspend fun ensureChecked() {
        if (_state.value != RegionRestrictionState.Unknown) return

        checkMutex.withLock {
            if (_state.value != RegionRestrictionState.Unknown) return

            if (!isChinaMainland()) {
                _state.value = RegionRestrictionState.Allowed
                return
            }

            _state.value = RegionRestrictionState.Checking
            _state.value = if (isChinaMainlandNetwork()) {
                RegionRestrictionState.Restricted
            } else {
                RegionRestrictionState.Allowed
            }
        }
    }

    private suspend fun isChinaMainlandNetwork(): Boolean {
        for (provider in GEO_IP_PROVIDERS) {
            val info = runCatching {
                withTimeout(PROVIDER_TIMEOUT_MILLIS.milliseconds) {
                    GLOBAL_CLIENT.get(provider).safeBodyAsJson<IpGeoInfo>()
                }
            }.onFailure {
                Logger.warning(TAG, "Failed to query IP geo location from $provider", it)
            }.getOrNull() ?: continue

            if (info.success == false) continue

            val countryCode = info.countryCode ?: info.country
            if (!countryCode.isNullOrBlank()) {
                return countryCode.equals("CN", ignoreCase = true)
            }
        }
        return false
    }

    /**
     * IP 归属服务的响应内容，兼容多个服务的字段差异
     */
    @Serializable
    private data class IpGeoInfo(
        /** ipwho.is 的请求是否成功 */
        val success: Boolean? = null,
        /** ipwho.is、ipapi.co 返回的国家代码 */
        @SerialName("country_code") val countryCode: String? = null,
        /** ipinfo.io 返回的国家代码 */
        val country: String? = null
    )
}