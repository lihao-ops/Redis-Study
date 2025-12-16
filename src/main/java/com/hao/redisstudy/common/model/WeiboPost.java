package com.hao.redisstudy.common.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data                // 自动生成 Getter, Setter, toString
@AllArgsConstructor  // 全参构造器
@NoArgsConstructor   // 无参构造器
public class WeiboPost {

    /** 微博ID (唯一标识) */
    private String postId;

    /** 发布人ID */
    private String userId;

    /** 微博内容 */
    private String content;

    /** 发布时间 (为了存JSON方便，这里直接存格式化好的String，如 "2023-10-01 12:00:00") */
    private String createTime;
    
    // 如果需要统计展示，也可以加这两个字段，但发布时默认为0
    // private int likeCount;
}