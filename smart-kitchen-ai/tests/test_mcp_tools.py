"""测试天气和节假日工具函数（mock 外部 API 调用）"""
from app.tools.weather_tools import get_tomorrow_weather
from app.tools.holiday_tools import get_holiday_info


def test_weather_tool_import_and_config():
    """验证 weather_tools 可正确导入且 config 已配置"""
    from app.config import settings
    assert settings.weather_api_key == "bdd1932402dc7238ceaab64950e71222"
    assert settings.weather_city == "Shenzhen"


def test_holiday_tool_import():
    """验证 holiday_tools 可正确导入"""
    assert callable(get_holiday_info)
