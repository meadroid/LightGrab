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

    // ---- 内部存储（私有，外部只通过 setXxx 修改）----
    private var _targetPkg: String = "com.sfexpress.knight"
    private var _dialogKeywords: List<String> = listOf("立即上门单")
    private var _btnKeywords: List<String> = listOf("确认抢单")
    private var _delayMin: Int = 100
    private var _delayMax: Int = 200

    // ---- 只读访问（GrabService 直接读这些）----
    val targetPkg: String get() = _targetPkg
    val dialogKeywords: List<String> get() = _dialogKeywords
    val btnKeywords: List<String> get() = _btnKeywords
    val delayMin: Int get() = _delayMin
    val delayMax: Int get() = _delayMax

    fun init(context: Context) {
        val s = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sp = s
        _targetPkg      = s.getString("targetPkg", _targetPkg) ?: _targetPkg
        _dialogKeywords = split(s.getString("dialogKeywords", join(_dialogKeywords)) ?: join(_dialogKeywords))
        _btnKeywords    = split(s.getString("btnKeywords", join(_btnKeywords)) ?: join(_btnKeywords))
        _delayMin       = s.getInt("delayMin", _delayMin)
        _delayMax       = s.getInt("delayMax", _delayMax)
    }

    // ---------- MainActivity 调用这些方法保存设置 ----------

    fun setTargetPkg(pkg: String) {
        _targetPkg = pkg.trim()
        sp?.edit()?.putString("targetPkg", _targetPkg)?.apply()
    }

    /** 输入"立即上门单,需及时上门" → 自动拆成两个词，命中任意一个就触发 */
    fun setDialogKeywords(text: String) {
        _dialogKeywords = split(text)
        sp?.edit()?.putString("dialogKeywords", join(_dialogKeywords))?.apply()
    }

    /** 输入"确认抢单,立即抢单" → 自动拆成两个词 */
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
