代码风格：缩进（空格/制表符）、命名规范（Java语言使用驼峰式/Python语言使用snake_case）。
语言与框架：优先使用的编程语言（Python/Java）
写代码时，需要考虑代码的可读性、可维护性和可扩展性。
每个方法前面需要加入注释，说明方法的功能和参数，方法名要简洁。
java代码使用驼峰式命名规范，python代码使用snake_case命名规范。
Java中如果涉及到常量类请统一使用枚举类，避免使用魔法数字。
分层原则：Controller 只做路由和参数接收，业务逻辑全部下沉到 Service 层。当前用户信息通过 UserContext（ThreadLocal）在 Service 层直接获取，不在 Controller 层手动传递 userId。