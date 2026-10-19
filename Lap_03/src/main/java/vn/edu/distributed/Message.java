package vn.edu.distributed;

// Clone để đồng hồ thay đổi không làm thay đổi timestamp trong bản tin.
public record Message(int senderId, int lamport, int[] vector) {
    public Message { vector = vector.clone(); }
    @Override
    public int[] vector() { return vector.clone(); }
}
