# Lab 03 — Lamport Clock và Vector Clock

Mô phỏng 3 tiến trình P0, P1, P2 bằng 3 thread Java. Truyền bản tin qua hàng
đợi trong bộ nhớ, không cần server, socket hay thư viện chạy ngoài.
Bản này chỉ giữ một kịch bản cố định để dễ đọc và trình bày.

## Chạy chương trình

Cần JDK 21+ và Maven. Từ thư mục gốc repository:

```powershell
cd Lap_03
mvn clean package
java -jar target/logical-clocks-sim-1.0.0.jar
```

Trong IDE, có thể chạy trực tiếp hàm main của lớp Main.
Chạy kiểm thử bằng `mvn test`.

## Đọc code theo thứ tự

1. **Main.java**: tạo 3 tiến trình, khởi động 3 thread và chờ hoàn thành.
   FutureTask chứa công việc của thread; get() chờ kết quả và báo lỗi về main.
2. **Process.java**: ba hành động localEvent(), send(target), receive().
   Mỗi tiến trình có hai đồng hồ và một inbox riêng.
3. **LamportClock.java**: một số nguyên và hai công thức cập nhật.
4. **VectorClock.java**: một mảng số nguyên, cập nhật và so sánh vector.
5. **Message.java**: ID người gửi và timestamp lúc gửi.
6. **EventRegistry.java**: in sự kiện, tự kiểm tra concurrency với sự kiện mới
   nhất của từng tiến trình khác.
7. **Logger.java**: in một dòng log.

## Quy tắc cần nhớ

Với tiến trình Pi:

| Sự kiện | Lamport | Vector |
|---|---|---|
| Local | L = L + 1 | V[i]++ |
| Send | L = L + 1 | V[i]++, gửi bản sao của V và L |
| Receive | L = max(L, L_msg) + 1 | max từng phần tử với V_msg, rồi V[i]++ |

Ví dụ P1 nhận bản tin từ P0:

- Trước nhận: L = 0, V = [0,0,0].
- Bản tin: L_msg = 2, V_msg = [2,0,0].
- Lamport sau nhận: max(0,2) + 1 = 3.
- Vector sau merge: [2,0,0]; tăng phần tử của P1 thành [2,1,0].

## Kịch bản và log thực tế

```text
[P0] LOCAL EVENT | Lamport: 1 | Vector: [1, 0, 0]
[P0] SEND MSG to P1 | Lamport: 2 | Vector: [2, 0, 0]
[P1] RECV MSG fr P0 | Lamport: 3 | Vector: [2, 1, 0]
[P2] LOCAL EVENT | Lamport: 1 | Vector: [0, 0, 1]
[P2] CONFLICT DETECTED with P0! Vector [0,0,1] || [2,0,0]
[P2] CONFLICT DETECTED with P1! Vector [0,0,1] || [2,1,0]
```

Có thêm conflict P2/P0 vì hai vector đó cũng đồng thời. Không hard-code
dòng conflict: sau mỗi sự kiện, EventRegistry gọi VectorClock.isConcurrent().

A <= B nghĩa là mọi A[k] <= B[k]. A xảy ra trước B khi A <= B và A khác B.
A || B khi cả A <= B lẫn B <= A đều sai. Vector bằng nhau không concurrent.
Với [0,0,1] và [2,1,0], phía thứ nhất lớn hơn ở vị trí 2 nhưng nhỏ hơn ở vị trí
0, 1, nên không bên nào <= bên kia.

Concurrent ở đây nghĩa là **không có quan hệ nhân quả theo bản tin**,
không nhất thiết chạy cùng thời điểm thực. task1.get() trong kịch bản P2 chỉ
điều phối thứ tự hiển thị của mô phỏng, không truyền timestamp sang P2.
Nếu tính cả đồng bộ Java như một kênh nhân quả của hệ thống thật thì phải
truyền đồng hồ qua kênh đó; lab này chỉ mô hình hóa kênh Message.

## Đồng bộ và phạm vi

- BlockingQueue truyền bản tin an toàn giữa các thread; nhận chờ tối đa 5 giây,
  hết thời gian thì báo lỗi.
- synchronized trong Process bảo vệ trọn việc cập nhật hai đồng hồ và ghi log.
- EventRegistry khóa việc in và so sánh để các sự kiện không chen log vào nhau.
- Mảng trong Message và các snapshot đều được clone để không chia sẻ dữ liệu
  có thể sửa với đồng hồ gốc.
- Bộ quan sát so sánh với **sự kiện mới nhất** của mỗi tiến trình, không lưu toàn
  bộ lịch sử. Đây là bộ quan sát tập trung phục vụ demo, không phải thuật toán
  phát hiện xung đột phân tán qua mạng.
- “CONFLICT” theo đề là phát hiện concurrency; ứng dụng thực tế cần thêm dữ liệu
  đọc/ghi để kết luận hai thao tác có xung đột dữ liệu hay không.

## Đối chiếu thang điểm

| Tiêu chí | Phần thực hiện |
|---|---|
| Lamport 25% | tick() và update() trong LamportClock |
| Vector 35% | tick() và merge() trong VectorClock |
| Concurrency 20% | isConcurrent() và kiểm tra tự động trong EventRegistry |
| Chất lượng và log 20% | 3 thread, inbox an toàn, synchronized, log cả hai đồng hồ |

Các kiểm thử đồng hồ kiểm tra max/increment, merge từng phần tử, snapshot,
quan hệ nhân quả và concurrency. Kiểm thử kịch bản kiểm tra log tự động thực tế.
