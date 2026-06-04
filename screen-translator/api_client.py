"""后端 API 客户端（仅健康检查）"""

import logging
import requests

logger = logging.getLogger(__name__)


class ApiClient:
    """与 Spring Boot 后端通信（仅健康检查用）"""

    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.session.timeout = 30

    def check_health(self) -> bool:
        """检查后端是否可达"""
        try:
            response = self.session.get(f"{self.base_url}/api/v1/llm/providers", timeout=5)
            return response.status_code == 200
        except requests.RequestException:
            return False
