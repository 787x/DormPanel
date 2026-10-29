package com.dormpanel.phone
import org.junit.Assert.*
import org.junit.Test
class RecentColumnRowsTest {
    data class Cell(val key: String,val text: String)
    data class Row(val cells: List<Cell>,val key: String="paired")
    fun select(rows: List<Row>,column: Int)=RecentColumnRows.select(rows,column,{ it.cells },{ it.text },{ it.key },{ key,cell -> Row(listOf(cell),key) })
    @Test fun unequalColumnsKeepAllTheirOwnCoursesAndNoBlankRows() {
        val rows=(0..9).map { n -> Row(listOf(Cell(if(n==0) "today" else "blank","${if(n==0) "Today" else ""}"),Cell("tomorrow-$n","Tomorrow $n"))) }
        assertEquals(1,select(rows,0).size); assertEquals(10,select(rows,1).size)
        assertEquals("tomorrow-9",select(rows,1).last().key)
    }
    @Test fun todayChangeDoesNotChangeTomorrowStableIdentityAndEmptyStateIsIndependent() {
        val before=listOf(Row(listOf(Cell("empty-today","今天没有课程"),Cell("tomorrow","Tomorrow"))))
        val after=listOf(Row(listOf(Cell("new-today","New today"),Cell("tomorrow","Tomorrow"))))
        assertEquals("今天没有课程",select(before,0).single().cells.single().text)
        assertEquals(select(before,1),select(after,1))
    }
}
