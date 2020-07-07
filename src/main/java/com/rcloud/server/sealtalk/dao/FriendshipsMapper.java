package com.rcloud.server.sealtalk.dao;

import com.rcloud.server.sealtalk.domain.Friendships;
import com.rcloud.server.sealtalk.domain.FriendshipsExample;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface FriendshipsMapper {
    long countByExample(FriendshipsExample example);

    int deleteByExample(FriendshipsExample example);

    int deleteByPrimaryKey(Integer id);

    int insert(Friendships record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int insertSelective(@Param("record") Friendships record, @Param("selective") Friendships.Column ... selective);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    Friendships selectOneByExample(FriendshipsExample example);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    Friendships selectOneByExampleSelective(@Param("example") FriendshipsExample example, @Param("selective") Friendships.Column ... selective);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    List<Friendships> selectByExampleSelective(@Param("example") FriendshipsExample example, @Param("selective") Friendships.Column ... selective);

    List<Friendships> selectByExample(FriendshipsExample example);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    Friendships selectByPrimaryKeySelective(@Param("id") Integer id, @Param("selective") Friendships.Column ... selective);

    Friendships selectByPrimaryKey(Integer id);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int updateByExampleSelective(@Param("record") Friendships record, @Param("example") FriendshipsExample example, @Param("selective") Friendships.Column ... selective);

    int updateByExample(@Param("record") Friendships record, @Param("example") FriendshipsExample example);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int updateByPrimaryKeySelective(@Param("record") Friendships record, @Param("selective") Friendships.Column ... selective);

    int updateByPrimaryKey(Friendships record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int batchInsert(@Param("list") List<Friendships> list);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int batchInsertSelective(@Param("list") List<Friendships> list, @Param("selective") Friendships.Column ... selective);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int upsert(Friendships record);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int upsertByExample(@Param("record") Friendships record, @Param("example") FriendshipsExample example);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int upsertByExampleSelective(@Param("record") Friendships record, @Param("example") FriendshipsExample example, @Param("selective") Friendships.Column ... selective);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int upsertSelective(@Param("record") Friendships record, @Param("selective") Friendships.Column ... selective);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int batchUpsert(@Param("list") List<Friendships> list);

    /**
     * This method was generated by MyBatis Generator.
     * This method corresponds to the database table friendships
     *
     * @mbg.generated
     * @project https://github.com/itfsw/mybatis-generator-plugin
     */
    int batchUpsertSelective(@Param("list") List<Friendships> list, @Param("selective") Friendships.Column ... selective);
}