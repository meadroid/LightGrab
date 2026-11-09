package com.light.grab

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置中心：MainActivity 写入，GrabService 实时读取
 * 所有设置自动持久化，重启 App/手机后不丢
 */
object Config {

    private const val FILE = "lightgrab_config"
    private var sp: SharedPreferences? = null

    // ---- 内部存储 ----
    private var _targetPkgs: List<String> = listOf("com.sfexpress.knight")
    private var _dialogKeywords: List<String> = listOf("立即上门单")
    private var _btnKeywords: List<String> = listOf("确认抢单")
    private var _delayMin: Int = 100
    private var _delayMax: Int = 200

    // ---- 只读访问 ----
    val targetPkgs: List<String> get() = _targetPkgs
    /** 兼容旧代码：第一个包名 */
    val targetPkg: String get() = _targetPkgs.firstOrNull() ?: "com.sfexpress.knight"
    val dialogKeywords: List<String> get() = _dialogKeywords
    val btnKeywords: List<String> get() = _btnKeywords
    val delayMin: Int get() = _delayMin
    val delayMax: Int get() = _delayMax

    /** 判断某包名是不是我们的目标（支持多包名） */
    fun isTarget(pkg: String?): Boolean {
        if (pkg == null) return false
        return _targetPkgs.contains(pkg)
    }

    fun init(context: Context) {
        val s = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = s
        _targetPkgs     = split(s.getString("targetPkgs", join(_targetPkgs)) ?: join(_targetPkgs))
        if (_targetPkgs.isEmpty()) _targetPkgs = listOf("com.sfexpress.knight")
        _dialogKeywords = split(s.getString("dialogKeywords", join(_dialogKeywords)) ?: join(_dialogKeywords))
        _btnKeywords    = split(s.getString("btnKeywords", join(_btnKeywords)) ?: join(_btnKeywords))
        _delayMin       = s.getInt("delayMin", _delayMin)
        _delayMax       = s.getInt("delayMax", _delayMax)
    }

    // ---------- MainActivity 调用这些方法保存设置 ----------

    /** 输入"com.sfexpress.knight,com.sfexpress.other" → 多包名同时监控 */
    fun setTargetPkg(text: String) {
        _targetPkgs = split(text)
        if (_targetPkgs.isEmpty()) _targetPkgs = listOf("com.sfexpress.knight")
        sp?.edit()?.putString("targetPkgs", join(_targetPkgs))?.apply()
    }

    /** 输入"立即上门单,需及时上门" → 命中任意一个就触发 */
    fun setDialogKeywords(text: String) {
        _dialogKeywords = split(text)
        sp?.edit()?.putString("dialogKeywords", join(_dialogKeywords))?.apply()
    }

    /** 输入"确认抢单,立即抢单" → 命中任意一个就点击 */
    fun setBtnKeywords(text: String) {
        _btnKeywords = split(text)
        sp?.edit()?.putString("btnKeywords", join(_btnKeywords))?.apply()
    }

    /** 延迟范围，单位 ms。自动保证 max > min、min >= 50 */
    fun setDelay(min: Int, max: Int) {
        _delayMin = min.coerceIn(50, 2000)
        _delayMax = max.coerceIn(_delayMin + 10, 3000)
        sp?.edit()?.putInt("delayMin", _delayMin)?.putInt("delayMax", _delayMax)?.apply()
    }

    // ---------- 工具 ----------
    private fun split(text: String) =
        text.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }

    private fun join(list: List<String>) = list.joinToString(",")
}
