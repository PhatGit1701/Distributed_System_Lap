# Thiết kế Lab 08

## Kiến trúc

Một process parent chứa client quorum proxy và bộ điều phối demo. Năm process
con S1-S5 có RAM riêng, TCP listener riêng và goroutine gossip riêng.
Parent thu READY qua stdout rồi gửi membership bằng TCP. Cổng do OS cấp.

## Gifford quorum

N=5 vote bằng nhau. Kiểm tra N_R+N_W>N và 2*N_W>N, mỗi threshold trong [1,N].
Read thu đủ N_R phản hồi và trả max version, gồm cả tombstone.
Write từ proxy duy nhất đọc quorum để cấp version mới rồi yêu cầu N_W ACK
thực sự chấp nhận đúng entry. Partial failure không được hiểu là rollback.

## Anti-entropy

Mỗi 2 giây, replica đang bật gossip chọn một peer ngẫu nhiên.
EXCHANGE đẩy full state sang peer, peer merge rồi trả full state để kéo về.
Merge giữ version cao hơn, từ chối hai payload khác nhau cùng version.
Snapshot có copy; map lock không bị giữ trong network I/O.

## Tombstone

Delete tạo entry mới với value rỗng, version tăng và is_tombstone=true.
Tombstone được đồng bộ như value; version cũ không thể ghi đè.
Giữ tombstone không thời hạn trong RAM, chưa GC vì chưa có stable-membership
acknowledgement chứng minh mọi replica đã quan sát deletion.

## Kiểm chứng và rubric

| Rubric | Thực hiện |
|---|---|
| Gifford 35% | validateQuorum, đọc max trong N_R, đủ N_W ACK, test giao nhau |
| Anti-entropy 30% | Push và Pull trong exchange, ticker random peer, test cả hai chiều |
| Tombstone 25% | version mới cho delete, gossip giữ tombstone, kiểm tra không hồi sinh |
| Documentation 10% | README, thiết kế này và trace chạy thực tế |

Demo dừng gossip đầu tiên để in rõ S4/S5 stale sau write quorum, chủ động
exchange để chứng minh hai chiều, sau đó bật ticker để kiểm chứng hội tụ.
Process được thu hồi sau khi xác minh. RAM-only, single writer và membership
cố định là những giới hạn được nêu rõ, không suy diễn thành consensus.
