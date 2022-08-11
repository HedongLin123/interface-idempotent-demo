package com.itdl.common.util;

import java.util.ArrayList;
import java.util.List;

/**
 * @Description 将大List拆分为小List
 * @Author itdl
 * @Date 2022/08/11 14:13
 */
public class CollectionUtil {
    /**
     *
     * @param list 要拆分的list
     * @param splitSize 拆分后的每个list大小
     * @param <T> 泛型
     * @return 拆分后的List
     */
    public static <T> List<List<T>> splitList(List<T> list, int splitSize) {
        List<List<T>> resultList = new ArrayList<>();
        // 开始索引
        int startIndex;
        // 结束索引
        int endIndex;
        // 计算要拆分的list个数
        int splitListSize = list.size() / splitSize;
        List<T> subList;
        for (int i = 0; i <= splitListSize; i++) {
            startIndex = splitSize * i;
            endIndex = startIndex + splitSize;
            if (i == splitListSize) {
                subList = list.subList(startIndex, list.size());
            } else {
                subList = list.subList(startIndex, endIndex);
            }
            if (subList.size() > 0) {
                resultList.add(subList);
            }
        }
        return resultList;
    }
}
