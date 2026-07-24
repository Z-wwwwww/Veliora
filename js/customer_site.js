// 内置采集源示例（默认不含任何实际源）
//
// 本项目是「中立的影视聚合搜索工具」，不预置、不分发任何视频内容源。
// 如需使用，请在应用「设置 → 自定义接口」中自行添加符合苹果 CMS V10 API
// 规范的采集源地址；或按下方结构在此文件中登记你自己的源。
//
// 结构示例（键名任意，value 为 { api, name }）：
//
// const CUSTOMER_SITES = {
//     example: {
//         api: 'https://example.com/api.php/provide/vod',
//         name: '示例源',
//     },
// };

const CUSTOMER_SITES = {};

// 调用全局方法合并
if (window.extendAPISites) {
    window.extendAPISites(CUSTOMER_SITES);
} else {
    console.error("错误：请先加载 config.js！");
}
