# Thiết kế Lab 07

## Mục tiêu và kiến trúc

Client có Session riêng. Proxy nằm ở phía client và liên lạc qua TCP với
ba replica S1-S3 có state riêng. Mỗi replica lưu clock, operation history
và register theo key. Replication nền chậm tạo các bản sao lệch nhau.

## Bất biến

1. Clock biểu diễn prefix thực sự đã áp dụng tại mỗi origin.
2. Session V_read/V_write chỉ tăng component-wise sau thao tác thành công.
3. READ chỉ thực hiện nếu replica.Clock >= max(V_read,V_write).
4. APPLY không bỏ qua dependency, không nhảy qua sequence bị thiếu.
5. Nếu repair không hoàn tất, không trả dữ liệu cũ cho session đó.

## Repair và xung đột

Proxy lấy các operation cần từ các replica, bỏ trùng ID và chuyển đến đích.
Batch được thử theo thứ tự nhân quả trên clock tạm trước khi áp dụng.
Register xử lý concurrent writes bằng thứ tự xác định; session validation
vẫn dùng quan hệ vector theo từng thành phần.

## Tính sẵn sàng và đồng bộ

Không có đủ write-set thì thao tác chờ/timeout. Mỗi RPC có deadline.
Mutex theo session và replica bảo vệ cập nhật nhiều bước; network server
thu hồi kết nối/goroutine khi đóng. Không chia sẻ dữ liệu giữa replica
thông qua map bộ nhớ.

## Kiểm chứng và rubric

| Rubric | Thực hiện |
|---|---|
| Vector tracking/validation 40% | Session, Vector.Covers, max của read/write, kiểm tra tại server |
| MR và RYW 30% | ensure trước Read, vector cập nhật sau thành công, demo độc lập hai bảo đảm |
| Fetch/recovery 20% | EXPORT/APPLY, causal prefix, chặn khi thiếu write-set |
| Documentation/design 10% | README, tài liệu này, trace và kiểm thử |

Recovery ở đây là sửa replica stale, không phải recovery dữ liệu sau crash.
Giới hạn: RAM, membership cố định, full history, không xác thực và không
cung cấp linearizability cho toàn bộ client.
