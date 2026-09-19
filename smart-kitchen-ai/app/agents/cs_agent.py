import os
from langchain_openai import ChatOpenAI
from langgraph.prebuilt import create_react_agent
from app.tools.dish_tools import (
    search_dish_by_preference, check_dish_inventory, get_dish_ingredients, get_dishes_realtime_info
)
from app.config import settings

PROMPTS_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "prompts")


def _load_prompt(filename: str) -> str:
    with open(os.path.join(PROMPTS_DIR, filename), "r", encoding="utf-8") as f:
        return f.read()


# Initialize LLM
llm = ChatOpenAI(
    api_key=settings.llm_api_key,
    base_url=settings.llm_base_url,
    model=settings.llm_model,
    streaming=True
)

# List of tools for the agent
tools = [
    search_dish_by_preference,
    check_dish_inventory,
    get_dish_ingredients,
    get_dishes_realtime_info
]

# Create the ReAct agent with function calling capabilities
cs_agent = create_react_agent(
    llm,
    tools,
    prompt=_load_prompt("cs_agent_prompt.txt")
)
