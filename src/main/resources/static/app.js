"use strict";

const byId = (id) => document.getElementById(id);
const room = byId("room");
const day = byId("day");
const period = byId("period");
const authForm = byId("auth-form");
const periodNames = { MORNING: "上午", AFTERNOON: "下午", EVENING: "晚上" };

let session = null;
let nextCursor = null;
let slotsRequest = 0;
let historyRequest = 0;
let notificationRequest = 0;
let notificationPoll;
let agentRequest = 0;

class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function feedback(id, message = "", error = false) {
  const node = byId(id);
  node.textContent = message;
  node.classList.toggle("error", error);
}

async function api(path, { method = "GET", body, token } = {}) {
  const headers = { Accept: "application/json, application/problem+json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = "Bearer " + token;
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const data = await response.json().catch(() => null);
  if (response.ok) return data;
  const defaults = { 401: "登录已失效或账号密码有误", 403: "当前账号没有此操作权限" };
  throw new ApiError(response.status, data?.detail || defaults[response.status] || ("请求失败（" + response.status + "）"));
}

function showSession(newSession) {
  session = newSession;
  nextCursor = null;
  historyRequest++;
  notificationRequest++;
  agentRequest++;
  clearInterval(notificationPoll);
  authForm.hidden = Boolean(session);
  byId("session-info").hidden = !session;
  byId("publish").hidden = session?.account.role !== "ADMIN";
  byId("history").hidden = session?.account.role !== "STUDENT";
  byId("notifications").hidden = session?.account.role !== "STUDENT";
  byId("agent").hidden = session?.account.role !== "STUDENT";
  byId("identity").textContent = session ? session.account.username + " · " +
    (session.account.role === "ADMIN" ? "管理员" : "学生") : "";
  byId("booking-list").replaceChildren();
  byId("notification-list").replaceChildren();
  byId("agent-form").reset();
  byId("agent-options").replaceChildren();
  byId("ask-agent").disabled = false;
  byId("more").hidden = true;
  feedback("booking-message");
  feedback("notification-message");
  feedback("agent-answer");
  if (session?.account.role === "STUDENT") {
    loadHistory();
    loadNotifications();
    notificationPoll = setInterval(loadNotifications, 5000);
  }
}

function sessionError(error, token) {
  if (error.status === 401 && token && session?.accessToken === token) {
    showSession(null);
    feedback("auth-message", "登录已过期，请重新登录。", true);
  }
}

async function authenticate(register) {
  const credentials = { username: byId("username").value, password: byId("password").value };
  const buttons = [...authForm.querySelectorAll("button")];
  buttons.forEach((button) => { button.disabled = true; });
  feedback("auth-message");
  try {
    if (register) await api("/api/auth/register", { method: "POST", body: credentials });
    const token = await api("/api/auth/login", { method: "POST", body: credentials });
    authForm.reset();
    showSession(token);
  } catch (error) {
    feedback("auth-message", error.message, true);
  } finally {
    buttons.forEach((button) => { button.disabled = false; });
  }
}

function slotQuery() {
  return "?date=" + encodeURIComponent(day.value) + "&period=" + encodeURIComponent(period.value);
}

async function loadSlots() {
  if (!room.value || !day.value || !day.checkValidity() || !period.value) {
    slotsRequest++;
    byId("seats").replaceChildren();
    byId("slot-summary").textContent = "请选择有效的日期和时段。";
    return;
  }
  const requestId = ++slotsRequest;
  byId("seats").replaceChildren();
  byId("slot-summary").textContent = "正在查询座位…";
  try {
    const slots = await api("/api/rooms/" + room.value + "/slots" + slotQuery());
    if (requestId !== slotsRequest) return;
    const count = slots.filter((slot) => slot.available).length;
    byId("slot-summary").textContent = slots.length ?
      count + " 个可预约 / 共 " + slots.length + " 个座位" : "该时段尚未开放。";
    byId("seats").replaceChildren(...slots.map(makeSeat));
  } catch (error) {
    if (requestId !== slotsRequest) return;
    byId("seats").replaceChildren();
    byId("slot-summary").textContent = error.message;
  }
}

function makeSeat(slot) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "seat";
  button.textContent = slot.seatLabel;
  button.disabled = !slot.available;
  button.setAttribute("aria-label", slot.seatLabel + (slot.available ? " 可预约" : " 不可预约"));
  button.addEventListener("click", () => reserve(slot, button));
  return button;
}

async function reserve(slot, button) {
  if (session?.account.role !== "STUDENT") {
    feedback("slot-message", "请用学生账号登录后预约。", true);
    return;
  }
  const token = session.accessToken;
  button.disabled = true;
  try {
    await api("/api/bookings", { method: "POST", body: { slotId: slot.id }, token });
    if (session?.accessToken === token) {
      feedback("slot-message", "已预约座位 " + slot.seatLabel + "。");
      await loadHistory();
    }
  } catch (error) {
    sessionError(error, token);
    feedback("slot-message", error.message, true);
  } finally {
    await loadSlots();
  }
}

async function publish() {
  if (!room.value || !day.value || !day.checkValidity() || !period.value) {
    feedback("slot-message", "请选择有效的日期和时段。", true);
    return;
  }
  const token = session?.accessToken;
  const button = byId("publish");
  button.disabled = true;
  try {
    const slots = await api("/api/admin/rooms/" + room.value + "/slots", {
      method: "PUT", body: { date: day.value, period: period.value }, token
    });
    feedback("slot-message", "该时段已开放 " + slots.length + " 个座位。");
    await loadSlots();
  } catch (error) {
    sessionError(error, token);
    feedback("slot-message", error.message, true);
  } finally {
    button.disabled = false;
  }
}

async function loadHistory(append = false) {
  if (session?.account.role !== "STUDENT") return;
  const token = session.accessToken;
  const requestId = ++historyRequest;
  const more = byId("more");
  more.disabled = true;
  try {
    const query = "?size=10" + (append ? "&beforeId=" + nextCursor : "");
    const { items, nextCursor: cursor } = await api("/api/bookings" + query, { token });
    if (requestId !== historyRequest) return;
    const list = byId("booking-list");
    if (!append) list.replaceChildren();
    list.append(...items.map(makeBooking));
    nextCursor = cursor;
    more.hidden = cursor == null;
    feedback("booking-message", list.children.length ? "" : "还没有预约记录。");
  } catch (error) {
    if (requestId !== historyRequest) return;
    sessionError(error, token);
    feedback("booking-message", error.message, true);
  } finally {
    if (requestId === historyRequest) more.disabled = false;
  }
}

function makeBooking(booking) {
  const row = document.createElement("li");
  row.className = "booking-row";
  const details = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = booking.roomName + " · " + booking.seatLabel;
  const date = document.createElement("span");
  date.textContent = booking.date + " " + periodNames[booking.period] + " · " +
    (booking.status === "CANCELLED" ? "已取消" : "已预约");
  details.append(title, date);
  row.append(details);
  if (booking.canCancel) {
    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "secondary";
    cancel.textContent = "取消预约";
    cancel.addEventListener("click", async () => {
      const token = session?.accessToken;
      cancel.disabled = true;
      try {
        await api("/api/bookings/" + booking.id + "/cancellation", { method: "PUT", token });
        feedback("slot-message", "已取消座位 " + booking.seatLabel + " 的预约。");
        await Promise.all([loadHistory(), loadSlots()]);
      } catch (error) {
        sessionError(error, token);
        feedback("booking-message", error.message, true);
      } finally {
        cancel.disabled = false;
      }
    });
    row.append(cancel);
  }
  return row;
}

async function loadNotifications() {
  if (session?.account.role !== "STUDENT") return;
  const token = session.accessToken;
  const requestId = ++notificationRequest;
  try {
    const notifications = await api("/api/notifications", { token });
    if (requestId !== notificationRequest) return;
    byId("notification-list").replaceChildren(...notifications.map(makeNotification));
    feedback("notification-message", notifications.length ? "" : "还没有预约通知。");
  } catch (error) {
    if (requestId !== notificationRequest) return;
    sessionError(error, token);
    feedback("notification-message", error.message, true);
  }
}

function makeNotification(notification) {
  const row = document.createElement("li");
  row.className = "booking-row";
  const details = document.createElement("div");
  const title = document.createElement("strong");
  const action = notification.type === "BOOKING_CREATED" ? "预约成功" : "已取消预约";
  title.textContent = action + " · " + notification.roomName + " · " + notification.seatLabel;
  const time = document.createElement("span");
  time.textContent = notification.occurredAt.replace("T", " ").slice(0, 19);
  details.append(title, time);
  row.append(details);
  return row;
}

async function askAgent(event) {
  event.preventDefault();
  if (session?.account.role !== "STUDENT") return;
  const message = byId("agent-question").value.trim();
  if (!message) {
    feedback("agent-answer", "请输入要查询的问题。", true);
    return;
  }
  const token = session.accessToken;
  const requestId = ++agentRequest;
  const button = byId("ask-agent");
  button.disabled = true;
  byId("agent-options").replaceChildren();
  feedback("agent-answer", "正在查询…");
  try {
    const result = await api("/api/agent/ask", { method: "POST", body: { message }, token });
    if (requestId !== agentRequest) return;
    feedback("agent-answer", result.answer);
    byId("agent-options").replaceChildren(...result.options.map(makeAgentOption));
  } catch (error) {
    if (requestId !== agentRequest) return;
    sessionError(error, token);
    feedback("agent-answer", error.message, true);
  } finally {
    if (requestId === agentRequest) button.disabled = false;
  }
}

function makeAgentOption(option) {
  const row = document.createElement("li");
  row.className = "booking-row";
  const details = document.createElement("div");
  const title = document.createElement("strong");
  title.textContent = option.roomName + " · " + option.availableCount + " 个可预约座位";
  const time = document.createElement("span");
  time.textContent = option.date + " " + periodNames[option.period] + " · " + option.sampleSeats.join("、");
  details.append(title, time);
  const view = document.createElement("button");
  view.type = "button";
  view.className = "secondary";
  view.textContent = "查看座位";
  view.addEventListener("click", () => {
    room.value = String(option.roomId);
    day.value = option.date;
    period.value = option.period;
    feedback("slot-message");
    loadSlots();
    byId("seat-title").scrollIntoView({ block: "start" });
    room.focus({ preventScroll: true });
  });
  row.append(details, view);
  return row;
}

async function init() {
  try {
    const [rooms, schedule] = await Promise.all([api("/api/rooms"), api("/api/schedule")]);
    for (const item of rooms) room.add(new Option(item.name + " · " + item.location, item.id));
    for (const block of schedule.periods) {
      period.add(new Option(periodNames[block.code] + " " + block.start.slice(0, 5) + "–" + block.end.slice(0, 5), block.code));
    }
    day.min = schedule.today;
    day.max = schedule.lastBookableDay;
    day.value = schedule.today < schedule.lastBookableDay
      ? new Date(Date.parse(schedule.today) + 86400000).toISOString().slice(0, 10) : schedule.today;
    byId("campus-time").textContent = "北京时间";
    for (const control of [room, day, period]) {
      control.disabled = false;
      control.addEventListener("change", () => {
        feedback("slot-message");
        loadSlots();
      });
    }
    await loadSlots();
  } catch (error) {
    byId("slot-summary").textContent = "无法加载自习室：" + error.message;
  }
}

authForm.addEventListener("submit", (event) => {
  event.preventDefault();
  authenticate(false);
});
byId("register").addEventListener("click", () => {
  if (authForm.reportValidity()) authenticate(true);
});
byId("logout").addEventListener("click", () => {
  showSession(null);
  feedback("auth-message", "已退出。");
});
byId("publish").addEventListener("click", publish);
byId("more").addEventListener("click", () => loadHistory(true));
byId("refresh-notifications").addEventListener("click", loadNotifications);
byId("agent-form").addEventListener("submit", askAgent);
for (const example of document.querySelectorAll("[data-agent-example]")) {
  example.addEventListener("click", () => {
    byId("agent-question").value = example.dataset.agentExample;
    byId("agent-question").focus();
  });
}
init();
