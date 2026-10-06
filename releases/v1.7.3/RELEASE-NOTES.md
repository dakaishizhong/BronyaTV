# BronyaTV 1.7.3

修复登录输入框弹出键盘后，遥控器上下左右仍移动页面焦点的问题。

- 账号、密码、服务器及共用输入框的方向键先交给输入法，确认键用于键盘选键。
- 输入法返回的未处理方向键不再移动背后的页面；键盘开关过渡期间保持这一规则。
- 返回先收起键盘并保留输入框焦点，之后恢复页面上下左右导航。
- 保持登录按钮向上返回密码框，以及用户卡片之间的遥控器导航。

1.7.3 沿用 1.7.1、1.7.2 的正式签名，可直接覆盖安装。1.7.0 或更早版本需要先卸载。

## English

Let the TV input method handle remote directions and confirmation while the keyboard is open. Prevent unhandled directions returned by the IME from moving the page behind it, including during keyboard show/hide transitions. Back closes the keyboard and restores page navigation.

The signing key is unchanged from 1.7.1 and 1.7.2; both can update directly.
