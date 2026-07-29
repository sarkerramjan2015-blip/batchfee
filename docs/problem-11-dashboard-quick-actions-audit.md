# Problem 11 — Dashboard Quick Actions audit

- The redundant six-card grid was in `DashboardScreen`, immediately after **Tools & reminders**. It directly navigated to `AddStudentRoute`, `AddBatchRoute`, `UnifiedCollectRoute`, `AttendanceRoute`, `AddExpenseRoute`, and `AddStaffRoute`.
- The same create actions already existed in the dashboard FAB's `AddNewMenuPanel`; all except Attendance were already represented there. Attendance was reachable via the More/module navigation, and is now included in the existing compact FAB menu as well.
- The FAB and panel already use `AccessControl.canAccessRoute`. The cleanup reuses that effective-permission behavior: unauthorized actions are filtered from the panel and the FAB is hidden if no central action is allowed.
- No removed card exposed a unique feature. Students, batches, fees, attendance, expenses and staff remain available through their modules; their routes are unchanged.
- The grid and its `ShortcutItem` renderer were removed. The existing post-section spacer remains, so summary cards, alerts, engagement/enquiry content, and Problem 03–04 loading/error behavior keep their current structure without a blank quick-action gap.
