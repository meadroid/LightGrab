package com.light.grab

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置中心：MainActivity 写入，GrabService 实时读取
 * 所有设置自动持久化，重启 App/手机后不丢
 */
object Config {

    private const val FILE = "lightgrab_config"
    private lateinit var sp: SharedPreferences

    // ---- 可自定义项（全部可在 App 界面改）----
    @Volatile var targetPkg: String = "com.sfexpress.knight"      // 目标应用包名
    @Volatile var dialogKeywords: List<String> = listOf("立即上门单")  // 触发词，多个用逗号隔开
    @Volatile var btnKeywords: List<String> = listOf("确认抢单")      // 按钮词，多个用逗号隔开
    @Volatile var delayMin: Int = 100   // 延迟下限 ms
    @Volatile var delayMax: Int = 200   // 延迟上限 ms

    fun init(context: Context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        targetPkg      = sp.getString("targetPkg", targetPkg) ?: targetPkg
        dialogKeywords = split(sp.getString("dialogKeywords", join(dialogKeywords)))
        btnKeywords    = split(sp.getString("btnKeywords", join(btnKeywords)))
        delayMin       = sp.getInt("delayMin", delayMin)
        delayMax       = sp.getInt("delayMax", delayMax)
    }

    // ---------- MainActivity 调用这些方法保存设置 ----------

    fun setTargetPkg(pkg: String) {
        targetPkg = pkg.trim()
        sp.edit().putString("targetPkg", targetPkg).apply()
    }

    /** 输入"立即上门单,需及时上门" → 自动拆成两个词，命中任意一个就触发 */
    fun setDialogKeywords(text: String) {
        dialogKeywords = split(text)
        sp.edit().putString("dialogKeywords", join(dialogKeywords)).apply()
    }

    /** 输入"确认抢单,立即抢单" → 自动拆成两个词 */
    fun setBtnKeywords(text: String) {
        btnKeywords = split(text)
        sp.edit().putString("btnKeywords", join(btnKeywords)).apply()
    }

    /** 延迟范围，单位 ms。自动保证 max > min、min >= 50 */
    fun setDelay(min: Int, max: Int) {
        delayMin = min.coerceIn(50, 2000)
        delayMax = max.coerceIn(delayMin + 10, 3000)
        sp.edit().putInt("delayMin", delayMin).putInt("delayMax", delayMax).apply()
    }

    // ---------- 工具 ----------
    private fun split(text: String) =
        text.split(",", "，", "、").map { it.trim() }.filter { it.isNotEmpty() }

    private fun join(list: List<String>) = list.joinToString(",")

    private fun <T> SharedPreferences.getString(k: String, d: T): String? =
        (this as SharedPreferences).getString(k, d as? String)
}
