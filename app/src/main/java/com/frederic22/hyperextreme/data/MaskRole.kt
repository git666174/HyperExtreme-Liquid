package com.frederic22.hyperextreme.data

/**
 * 三个可替换的模板文件。
 *
 * [id] 必须与 com.miui.aod 内部使用的文件名完全一致 —— hook 侧是按路径末段匹配的，
 * 改名会让 ThemeUtil 的 `endsWith("subject_mask")` 判定失效，景深功能会直接不可用。
 */
enum class MaskRole(
    val id: String,
    val fileName: String,
    val title: String,
    val summary: String,
    /** 是否在界面上让用户选图。crop_subject 由模块跟随 subject 自动写入，不给入口。 */
    val userFacing: Boolean = true,
) {
    SUBJECT(
        id = "subject",
        fileName = "subject.png",
        title = "主体",
        summary = "背景透明的主体图片",
    ),
    SUBJECT_MASK(
        id = "subject_mask",
        fileName = "subject_mask.png",
        title = "遮罩",
        summary = "颜色黑白的灰度遮罩",
    ),
    CROP_SUBJECT(
        id = "crop_subject",
        fileName = "crop_subject.png",
        title = "渲染层",
        summary = "按壁纸裁切后的最终抠像，由模块自动生成。",
        userFacing = false,
    ),

    ;

    companion object {
        /** 界面上可选的三个里去掉 crop_subject。 */
        val selectable: List<MaskRole> get() = entries.filter { it.userFacing }
    }
}
