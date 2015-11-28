图片有三个存放位置
1. 内存（内存也可分为两级，一级为强引用，一级为软引用，使用LruCache实现）
2. 硬盘（使用DiskLruCache实现）
3. 网络

二级内存的实现（LruCache）：

第一级内存使用强引用，第二级内存使用软引用。
并且，第一级内存扩展LruCache类，并重载entryRemoved方法，该方法被调用表示元素被移除，那么把它加入到第二季内存中。 

异步加载图片及其优化

1. 在getView中首先设置默认加载图片
2. 设置View的tag为新的url，并首先从内存加载数据，如果没有找到，从软引用中查找，最后，则启动异步任务加载
3. 在异步任务中，首先从硬盘加载数据，如果没有找到，则从网络加载数据
4. 如果加载数据不为null，把加载的数据依次存放在硬盘缓冲和内存缓冲中，并更新UI。
5. 这个时候就要判断View是否可见。如果不可见，则不更新；否则更新。为了在postExecute中获取url相关的view，可以通过ListView的findViewWithTag方法获取，tag即为上一步设置的url。
6. 另外，当滚动时（可通过ListView的OnScrollListener监听器监听），只从内存加载数据，不从网络加载；
7. 可以使用线程池加载数据