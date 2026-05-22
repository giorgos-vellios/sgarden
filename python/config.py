import os
from pydantic_settings import BaseSettings
from dotenv import load_dotenv

load_dotenv(os.path.join(os.path.dirname(__file__), "..", ".env"))


class Settings(BaseSettings):
    database_url: str = "mongodb+srv://walkathon:walkathon@walkathon.xwkvogr.mongodb.net/Walkathon?appName=Walkathon"
    port: int = 4000
    server_secret: str = "sgarden-secret-key"
    jwt_expiration_hours: int = 24

    class Config:
        env_file = "../.env"
        extra = "ignore"


settings = Settings()
